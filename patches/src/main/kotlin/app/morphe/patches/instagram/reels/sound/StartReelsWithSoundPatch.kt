/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.sound

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsConstString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.sound.findAudioState
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Start reels with sound"
internal const val REEL_SOUND = "$EXTENSION_PACKAGE/reels/ReelSound;"
internal const val REEL_WANTED = "$REEL_SOUND->wanted()I"
internal const val REEL_START_WITH_SOUND = "$REEL_SOUND->startWithSound(Ljava/lang/Object;Ljava/lang/Object;)I"
internal const val REEL_OPENED = "$REEL_SOUND->opened(Ljava/lang/Object;I)I"
internal const val REEL_PAUSING = "$REEL_SOUND->pausing(Ljava/lang/Object;)I"
internal const val REEL_PAUSED = "$REEL_SOUND->paused(Ljava/lang/Object;I)I"
internal const val REEL_RESUMING = "$REEL_SOUND->resuming(Ljava/lang/Object;)I"
internal const val REEL_LETS_RESET = "$REEL_SOUND->letsReset(Ljava/lang/Boolean;Ljava/lang/Boolean;)I"
internal const val REEL_LETS_SOUND_ON = "$REEL_SOUND->letsSoundOn(I)I"

/** The flag name Instagram's check for playing a reel's sound as the app opens loads, which its build keeps. */
internal const val SOUND_ON_APP_OPEN = "homecoming_sound_play_on_app_open"

/** The lifecycle callback the audio state's foreground listener implements, a name the build keeps. */
internal const val ON_APP_FOREGROUNDED = "onAppForegrounded"

/** The trace name the Reels viewer's `onCreate` loads, which Instagram's build keeps. */
internal const val VIEWER_TRACE = "ClipsViewerFragment.onCreate"
internal const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
internal const val VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val BUNDLE = "Landroid/os/Bundle;"
private const val BOOLEAN = "Ljava/lang/Boolean;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The Reels viewer's `onCreate(Bundle)`: the one that loads [VIEWER_TRACE]. */
internal object ReelsViewerOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(BUNDLE),
    custom = { method, _ -> method.loadsConstString(VIEWER_TRACE) },
)

/**
 * Opens a reel tapped from a post on Home with its sound on (#342), through Instagram's own audio
 * state, the one `Start stories with sound` sets.
 *
 * Home keeps the account's audio state muted while you scroll, and the Reels viewer plays by that
 * state, so a reel opened from a feed post starts muted. As the Reels viewer is created the patch
 * asks the extension whether the switch is on, and only then reads where the viewer was opened from
 * (its config's `ClipsViewerSource`) and asks again with it. A yes for a viewer opened from Home sets
 * the state to on with the setter the Reels audio toggle uses, so the speaker icon agrees. Its
 * `onPause` puts the sound back to off unless you muted the reel, so Home is muted again, and its
 * `onResume` turns it on for the reel. The Reels tab and every other source keep Instagram's own.
 *
 * Instagram changes the state by itself as the app opens: its foreground listener clears it to the
 * defaults the first time and after ten minutes away, and turns it on when other media plays, and
 * its check for a reel opened with the app just opened turns it on too. A reel opened from a link
 * on a cold start was set muted a moment before all three, so it played with sound. Each call asks
 * the extension first, which keeps the state off while Start reels muted is on (#342).
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val startReelsWithSoundPatch = bytecodePatch(
    name = "Start reels with sound",
    description = "A reel you open from a post on Home plays with sound, and Home is muted again when you go " +
        "back. Your phone's ringer and volume still count. A separate switch, Start reels muted, starts every reel silent " +
        "instead. Both start off. Turn them on in HushGram settings > Playback.",
) {
    category("Playback")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelSound")
        applyReelSound(findReelSoundSite())
        enableStatus("reelSound")
    }
}

/**
 * The Reels viewer's `onCreate` and the index right after its super call, its config getter and
 * the config's source field, its session getter, the audio state's static factory, boolean getter
 * and setter, the three locals the hook borrows there, and the viewer's `onPause` and `onResume`
 * with the locals their hooks borrow at their first instruction. Then Instagram's own changes to the
 * state as the app opens: the foreground listener's clear with the state's value field, and the
 * calls turning it on by itself.
 */
internal class ReelSoundSite(
    val onCreate: Method,
    val index: Int,
    val config: MethodReference,
    val source: FieldReference,
    val session: MethodReference,
    val factory: MethodReference,
    val getter: MethodReference,
    val setter: MethodReference,
    val scratch: List<Int>,
    val onPause: Method,
    val pauseScratch: List<Int>,
    val onResume: Method,
    val resumeScratch: List<Int>,
    val reset: StateCall,
    val value: FieldReference,
    val soundOns: List<StateCall>,
)

/**
 * A call of Instagram's own changing the audio state: [method], the call's [index], the register
 * holding the state ([state]) and the one holding the new value ([value]), and a local the hook
 * borrows there.
 */
internal class StateCall(val method: Method, val index: Int, val state: Int, val value: Int, val scratch: Int)

internal fun BytecodePatchContext.findReelSoundSite(): ReelSoundSite {
    val creates = ReelsViewerOnCreateFingerprint.matchAllOrNull().orEmpty().map { it.method }
        .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
        .distinctBy { it.definingClass }
    val onCreate = creates.singleOrNull()
        ?: refuse("expected one onCreate(Bundle) loading \"$VIEWER_TRACE\", found ${creates.size}")
    val viewerType = onCreate.definingClass
    val viewer = classDefBy(viewerType)
    val where = "$viewerType->onCreate"
    val code = onCreate.code()
    val supers = code.indices.filter { at ->
        val called = code[at].methodReference()
        code[at].opcode == Opcode.INVOKE_SUPER && called?.name == "onCreate" && called.returnType == "V" &&
            called.parameterTypes.map(Any::toString) == listOf(BUNDLE)
    }
    val superCall = supers.singleOrNull() ?: refuse("$where doesn't call super.onCreate once, found ${supers.size}")
    val index = superCall + 1
    if (index !in code.indices) refuse("$where ends on its super call")
    if (index in onCreate.jumpTargets()) refuse("something in $where jumps to just past its super call")
    onCreate.requireThisIntact(PATCH, listOf(index))

    val config = viewer.ownGetter(VIEWER_CONFIG)
    val session = viewer.ownGetter(USER_SESSION)
    val configClass = classDefByOrNull(VIEWER_CONFIG) ?: refuse("Instagram has no $VIEWER_CONFIG")
    val sources = configClass.fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuse("expected one $VIEWER_SOURCE field in $VIEWER_CONFIG, found ${sources.size}")
    if (!AccessFlags.PUBLIC.isSet(source.accessFlags) || !AccessFlags.PUBLIC.isSet(configClass.accessFlags)) {
        refuse("$VIEWER_CONFIG->${source.name} can't be read from the Reels viewer")
    }

    val (factory, getter, setter) = findAudioState()
    // The injected code is in the viewer's package: each piece must be reachable from there.
    val package0 = viewerType.substringBeforeLast('/')
    val stateType = factory.returnType
    val state = classDefByOrNull(stateType) ?: refuse("Instagram has no $stateType")
    fun open(flags: Int) = stateType.substringBeforeLast('/') == package0 ||
        (AccessFlags.PUBLIC.isSet(flags) && AccessFlags.PUBLIC.isSet(state.accessFlags))
    val reachable = listOf(factory, getter, setter).all { ref ->
        state.methods.singleOrNull {
            it.name == ref.name && it.returnType == ref.returnType &&
                it.parameterTypes.map(Any::toString) == ref.parameterTypes.map(Any::toString)
        }?.let { open(it.accessFlags) } == true
    }
    if (!reachable) refuse("$where can't reach the audio state $stateType from the Reels viewer")

    // One more local each for the setter's second flag, which 453 added.
    val scratch = onCreate.freeLocalsAt(PATCH, index, 4)
    val onPause = viewer.lifecycle("onPause")
    val onResume = viewer.lifecycle("onResume")

    // The foreground listener is the state's own: the type of one of its fields.
    val listeners = state.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.distinct()
        .mapNotNull { type ->
            classDefByOrNull(type)?.methods?.singleOrNull {
                it.name == ON_APP_FOREGROUNDED && it.returnType == "V" && it.parameterTypes.isEmpty() &&
                    !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
            }
        }
    val foreground = listeners.singleOrNull()
        ?: refuse("expected one field of $stateType with an $ON_APP_FOREGROUNDED() of its own, found ${listeners.size}")
    val reset = foreground.stateCall(Opcode.INVOKE_STATIC, listOf(stateType, BOOLEAN), stateType, "clear")
    val values = state.fields.filter { it.type == BOOLEAN && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val value = values.singleOrNull() ?: refuse("expected one $BOOLEAN field in $stateType, found ${values.size}")
    val samePackage = stateType.substringBeforeLast('/') == foreground.definingClass.substringBeforeLast('/')
    if (AccessFlags.PRIVATE.isSet(value.accessFlags) ||
        !(samePackage || (AccessFlags.PUBLIC.isSet(value.accessFlags) && AccessFlags.PUBLIC.isSet(state.accessFlags)))
    ) {
        refuse("$stateType->${value.name} can't be read from ${foreground.definingClass}")
    }

    // The check for a reel opened with the app just opened, in the class loading its flag's name.
    val checks = classesLoadingString(SOUND_ON_APP_OPEN).flatMap { it.methods }.filter { method ->
        method.code().any { it.opcode == Opcode.INVOKE_VIRTUAL && it.methodReference()?.setsSound(stateType) == true }
    }
    val check = checks.singleOrNull()
        ?: refuse("expected one method in the classes loading \"$SOUND_ON_APP_OPEN\" to set $stateType, found ${checks.size}")

    return ReelSoundSite(
        onCreate, index, config, ImmutableFieldReference(VIEWER_CONFIG, source.name, VIEWER_SOURCE),
        session, factory, getter, setter, scratch,
        onPause, onPause.freeLocalsAt(PATCH, 0, 4), onResume, onResume.freeLocalsAt(PATCH, 0, 3),
        reset, ImmutableFieldReference(stateType, value.name, BOOLEAN),
        listOf(
            foreground.stateCall(Opcode.INVOKE_VIRTUAL, listOf("Z"), stateType, "turn-on"),
            check.stateCall(Opcode.INVOKE_VIRTUAL, listOf("Z"), stateType, "turn-on"),
        ),
    )
}

/** Whether this is one of [stateType]'s instance setters, taking the new value as a boolean. */
private fun MethodReference.setsSound(stateType: String) =
    definingClass == stateType && returnType == "V" && parameterTypes.map(Any::toString) == listOf("Z")

/**
 * The one [opcode] call in this method to a [stateType] method taking [parameters] and returning
 * nothing, with two registers, the state's and the new value's, and a local the hook can borrow
 * there. Nothing may jump to the call, since the hook goes in front of it.
 */
private fun Method.stateCall(opcode: Opcode, parameters: List<String>, stateType: String, what: String): StateCall {
    val where = "$definingClass->$name"
    val code = code()
    val calls = code.indices.filter { at ->
        val called = code[at].methodReference()
        code[at].opcode == opcode && called?.definingClass == stateType && called.returnType == "V" &&
            called.parameterTypes.map(Any::toString) == parameters
    }
    val at = calls.singleOrNull() ?: refuse("expected one sound $what in $where, found ${calls.size}")
    val call = code[at] as FiveRegisterInstruction
    if (call.registerCount != 2) refuse("the sound $what in $where passes ${call.registerCount} registers")
    if (at + 1 !in code.indices) refuse("$where ends on its sound $what")
    if (at in jumpTargets()) refuse("something in $where jumps to its sound $what")
    val scratch = freeLocalsAt(PATCH, at, 1, targets = listOf(at + 1)).single()
    return StateCall(this, at, call.registerC, call.registerD, scratch)
}

/** The viewer's own one public no-argument instance method returning [type]. */
private fun ClassDef.ownGetter(type: String): MethodReference {
    val found = methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == type &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.BRIDGE.isSet(it.accessFlags) &&
            !AccessFlags.SYNTHETIC.isSet(it.accessFlags) && it.implementation != null
    }
    val getter = found.singleOrNull() ?: refuse("expected one public no-argument $type getter in ${this.type}, found ${found.size}")
    return ImmutableMethodReference(this.type, getter.name, emptyList(), type)
}

/** The viewer's own no-argument [name], whose first instruction nothing jumps back to. */
private fun ClassDef.lifecycle(name: String): Method {
    val method = methods.singleOrNull {
        it.name == name && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$type has no $name() of its own")
    if (0 in method.jumpTargets()) refuse("something in $type->$name jumps back to its first instruction")
    method.requireThisIntact(PATCH, listOf(0))
    return method
}

/**
 * Right after the viewer's super call: ask [REEL_WANTED], and on a yes read the viewer's source and
 * ask [REEL_START_WITH_SOUND] with it. On a second yes take the account's audio state from the
 * viewer's session, tell [REEL_OPENED] whether it was on, and set it to on. At the top of `onPause`,
 * when [REEL_PAUSING] says this viewer turned it on, read it and set it to off on a yes from
 * [REEL_PAUSED]; at the top of `onResume`, set it to on again on a yes from [REEL_RESUMING]. Any no
 * along the way goes on to Instagram's own code.
 */
internal fun BytecodePatchContext.applyReelSound(site: ReelSoundSite) {
    // 453's setter also takes whether the user changed it, false here as in Start stories with sound.
    fun setter(state: Int, value: Int, extra: Int) = if (site.setter.parameterTypes.size == 2) {
        "const/4 v$extra, 0x0\n                invoke-virtual { v$state, v$value, v$extra }, ${site.setter}"
    } else {
        "invoke-virtual { v$state, v$value }, ${site.setter}"
    }
    val (flag, state, viewer, setterExtra) = site.scratch
    mutable(site.onCreate).apply {
        addInstructionsWithLabels(
            site.index,
            """
                invoke-static { }, $REEL_WANTED
                move-result v$flag
                if-eqz v$flag, :stock
                invoke-virtual/range { p0 .. p0 }, ${site.config}
                move-result-object v$state
                if-eqz v$state, :stock
                iget-object v$state, v$state, ${site.source}
                move-object/from16 v$viewer, p0
                invoke-static { v$viewer, v$state }, $REEL_START_WITH_SOUND
                move-result v$flag
                if-eqz v$flag, :stock
                invoke-virtual/range { p0 .. p0 }, ${site.session}
                move-result-object v$state
                if-eqz v$state, :stock
                invoke-static { v$state }, ${site.factory}
                move-result-object v$state
                invoke-virtual { v$state }, ${site.getter}
                move-result v$flag
                invoke-static { v$viewer, v$flag }, $REEL_OPENED
                move-result v$flag
                ${setter(state, flag, setterExtra)}
            """,
            ExternalLabel("stock", getInstruction(site.index)),
        )
    }
    val (pFlag, pState, pViewer, pSetterExtra) = site.pauseScratch
    mutable(site.onPause).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $REEL_PAUSING
                move-result v$pFlag
                if-eqz v$pFlag, :stock
                invoke-virtual/range { p0 .. p0 }, ${site.session}
                move-result-object v$pState
                if-eqz v$pState, :stock
                invoke-static { v$pState }, ${site.factory}
                move-result-object v$pState
                invoke-virtual { v$pState }, ${site.getter}
                move-result v$pFlag
                move-object/from16 v$pViewer, p0
                invoke-static { v$pViewer, v$pFlag }, $REEL_PAUSED
                move-result v$pFlag
                if-eqz v$pFlag, :stock
                const/4 v$pFlag, 0
                ${setter(pState, pFlag, pSetterExtra)}
            """,
            ExternalLabel("stock", getInstruction(0)),
        )
    }
    val (rFlag, rState, rSetterExtra) = site.resumeScratch
    mutable(site.onResume).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $REEL_RESUMING
                move-result v$rFlag
                if-eqz v$rFlag, :stock
                invoke-virtual/range { p0 .. p0 }, ${site.session}
                move-result-object v$rState
                if-eqz v$rState, :stock
                invoke-static { v$rState }, ${site.factory}
                move-result-object v$rState
                const/4 v$rFlag, 1
                ${setter(rState, rFlag, rSetterExtra)}
            """,
            ExternalLabel("stock", getInstruction(0)),
        )
    }
    // Instagram's own changes skip their call on a no. The later call in a method goes in first, so
    // the foreground listener's clear keeps its index.
    (site.soundOns + site.reset).sortedByDescending { it.index }.forEach { call ->
        val s = call.scratch
        val ask = if (call === site.reset) {
            """
                iget-object v$s, v${call.state}, ${site.value}
                invoke-static { v$s, v${call.value} }, $REEL_LETS_RESET
            """
        } else {
            "invoke-static { v${call.value} }, $REEL_LETS_SOUND_ON"
        }
        mutable(call.method).apply {
            addInstructionsWithLabels(
                call.index,
                """
                    $ask
                    move-result v$s
                    if-eqz v$s, :skip
                """,
                ExternalLabel("skip", getInstruction(call.index + 1)),
            )
        }
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

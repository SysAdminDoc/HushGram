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
internal const val REEL_OPENED = "$REEL_SOUND->opened(Ljava/lang/Object;I)V"
internal const val REEL_PAUSING = "$REEL_SOUND->pausing(Ljava/lang/Object;)I"
internal const val REEL_PAUSED = "$REEL_SOUND->paused(Ljava/lang/Object;I)I"
internal const val REEL_RESUMING = "$REEL_SOUND->resuming(Ljava/lang/Object;)I"

/** The trace name the Reels viewer's `onCreate` loads, which Instagram's build keeps. */
internal const val VIEWER_TRACE = "ClipsViewerFragment.onCreate"
internal const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
internal const val VIEWER_SOURCE = "Lcom/instagram/clips/intf/ClipsViewerSource;"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val BUNDLE = "Landroid/os/Bundle;"

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
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val startReelsWithSoundPatch = bytecodePatch(
    name = "Start reels with sound",
    description = "A reel you open from a post on Home plays with sound, and Home is muted again when you go " +
        "back. Your phone's ringer and volume still count. Starts off. Turn it on in HushGram settings > Playback.",
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
 * with the locals their hooks borrow at their first instruction.
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
)

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
    return ReelSoundSite(
        onCreate, index, config, ImmutableFieldReference(VIEWER_CONFIG, source.name, VIEWER_SOURCE),
        session, factory, getter, setter, scratch,
        onPause, onPause.freeLocalsAt(PATCH, 0, 4), onResume, onResume.freeLocalsAt(PATCH, 0, 3),
    )
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
                const/4 v$flag, 1
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
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.sound

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
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.tapvolume.TOGGLE_AUDIO
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Start stories with sound"
internal const val STORY_SOUND = "$EXTENSION_PACKAGE/stories/StorySound;"
internal const val START_WITH_SOUND = "$STORY_SOUND->startWithSound(Ljava/lang/Object;)I"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val BUNDLE = "Landroid/os/Bundle;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Opens stories with their sound on, through Instagram's own audio state.
 *
 * Instagram 450 keeps one audio state per account: a small object the speaker icon and the volume
 * keys flip, that a story's player reads when it starts (with nothing saved it plays with sound only
 * while the phone's ringer is on and its volume is up). A muted state left behind by a reel or a
 * feed video therefore silences the next story. The patch asks the extension once, as the story
 * viewer is created. A yes sets that state to on with its own setter, the call the Reels audio
 * toggle makes when it unmutes, so the speaker icon and every later tap agree with what plays. The
 * extension answers no on a silent or vibrating phone and at zero media volume, so Instagram keeps
 * its own choice there. A no, the switch off, Pause and a settings screen that isn't ready all leave
 * Instagram's code exactly as it was.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val startStoriesWithSoundPatch = bytecodePatch(
    name = "Start stories with sound",
    description = "Opens stories with their sound on, the same as tapping Instagram's speaker. Your phone's ringer " +
        "and volume still count. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storySound")
        applyStorySound(findStorySoundSite())
        enableStatus("storySound")
    }
}

/**
 * The story viewer's `onCreate` and the index right after its super call, the viewer's session
 * getter, the audio state's static factory and its boolean setter, and the two locals the hook may
 * borrow there.
 */
internal class StorySoundSite(
    val onCreate: Method,
    val index: Int,
    val session: MethodReference,
    val factory: MethodReference,
    val setter: MethodReference,
    val scratch: List<Int>,
)

/**
 * Instagram's account audio state is the object the Reels audio toggle ([TOGGLE_AUDIO]) takes from
 * the user session with a public static, asks whether sound is on, and sets to the opposite with a
 * public boolean setter. The story viewer's sound check, the one `(Integer, boolean, boolean)`
 * boolean it asks of an object it holds, reads that same object through the same static, which this
 * proves before anything changes. The hook goes in the viewer's `onCreate` right after its super
 * call, where the session getter works and no jump lands.
 */
internal fun BytecodePatchContext.findStorySoundSite(): StorySoundSite {
    val viewer = classDefByOrNull(STORY_VIEWER) ?: refuse("Instagram has no $STORY_VIEWER")
    val onCreate = viewer.methods.singleOrNull {
        it.name == "onCreate" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(BUNDLE) &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$STORY_VIEWER has no onCreate(Bundle) of its own")
    val where = "$STORY_VIEWER->onCreate"
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

    val session = findSessionGetter(viewer.superclass)
    val (factory, setter) = findAudioState()
    requireViewerReadsIt(factory)

    // The injected code is in the viewer's package: each piece must be reachable from there.
    val package0 = STORY_VIEWER.substringBeforeLast('/')
    fun open(type: String, flags: Int): Boolean {
        val declared = classDefByOrNull(type) ?: return false
        return type.substringBeforeLast('/') == package0 ||
            (AccessFlags.PUBLIC.isSet(flags) && AccessFlags.PUBLIC.isSet(declared.accessFlags))
    }
    val stateType = factory.returnType
    val factoryMethod = classDefBy(stateType).methods.singleOrNull {
        it.name == factory.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) && it.returnType == stateType
    }
    val setterMethod = classDefBy(stateType).methods.singleOrNull {
        it.name == setter.name && !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf("Z")
    }
    if (factoryMethod == null || setterMethod == null || !open(stateType, factoryMethod.accessFlags) ||
        !open(stateType, setterMethod.accessFlags)
    ) {
        refuse("$where can't reach the audio state $stateType from the story viewer")
    }

    val scratch = onCreate.freeLocalsAt(PATCH, index, 2)
    return StorySoundSite(onCreate, index, session, factory, setter, scratch)
}

/**
 * The viewer's session getter: the one public instance method with no parameters that returns the
 * user session in the viewer's superclass chain. It's called from the viewer as
 * `STORY_VIEWER->name()`, so only the name and that it's public matter.
 */
private fun BytecodePatchContext.findSessionGetter(start: String?): MethodReference {
    val found = mutableListOf<Method>()
    var type = start
    var depth = 0
    while (type != null && depth++ < 12) {
        val classDef = classDefByOrNull(type) ?: break
        classDef.methods.filterTo(found) {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == USER_SESSION &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.BRIDGE.isSet(it.accessFlags) &&
                !AccessFlags.SYNTHETIC.isSet(it.accessFlags)
        }
        type = classDef.superclass
    }
    val getter = found.singleOrNull()
        ?: refuse("expected one public no-argument $USER_SESSION getter above $STORY_VIEWER, found ${found.size}")
    return ImmutableMethodReference(STORY_VIEWER, getter.name, emptyList(), USER_SESSION)
}

/**
 * The Reels audio toggle ([TOGGLE_AUDIO]) takes the audio state from the session (a static taking
 * the session), asks it whether sound is on (a no-argument boolean), flips that with an xor and
 * hands the result to a boolean setter on the same object. Found once, or the patch stops.
 */
private fun BytecodePatchContext.findAudioState(): Pair<MethodReference, MethodReference> {
    val toggles = mutableListOf<Method>()
    val marked = typesMarked(TOGGLE_AUDIO)
    classDefForEach { classDef ->
        if (classDef.type !in marked) return@classDefForEach
        classDef.methods.forEach { method -> if (TOGGLE_AUDIO in method.markers()) toggles += method }
    }
    val toggle = toggles.singleOrNull() ?: refuse("the Reels audio toggle: expected one method marked $TOGGLE_AUDIO, found ${toggles.size}")
    val code = toggle.code()
    val found = mutableListOf<Pair<MethodReference, MethodReference>>()
    for (at in code.indices) {
        val factory = code[at].methodReference() ?: continue
        if (code[at].opcode != Opcode.INVOKE_STATIC || factory.parameterTypes.map(Any::toString) != listOf(USER_SESSION)) continue
        val stateType = factory.returnType
        val state = code.getOrNull(at + 1) as? OneRegisterInstruction
        val asks = code.getOrNull(at + 2)?.methodReference()
        val answer = code.getOrNull(at + 3) as? OneRegisterInstruction
        val flip = code.getOrNull(at + 4)
        val sets = code.getOrNull(at + 5)?.methodReference()
        val setsCall = code.getOrNull(at + 5) as? FiveRegisterInstruction
        if (code[at + 1].opcode != Opcode.MOVE_RESULT_OBJECT || code.getOrNull(at + 2)?.opcode != Opcode.INVOKE_VIRTUAL ||
            asks?.definingClass != stateType || asks.returnType != "Z" || asks.parameterTypes.isNotEmpty() ||
            answer == null || code[at + 3].opcode != Opcode.MOVE_RESULT ||
            flip?.opcode != Opcode.XOR_INT_LIT8 || (flip as NarrowLiteralInstruction).narrowLiteral != 1 ||
            (flip as TwoRegisterInstruction).registerB != answer.registerA ||
            code.getOrNull(at + 5)?.opcode != Opcode.INVOKE_VIRTUAL || sets?.definingClass != stateType || sets.returnType != "V" ||
            sets.parameterTypes.map(Any::toString) != listOf("Z") ||
            setsCall == null || setsCall.registerCount != 2 || state == null || setsCall.registerC != state.registerA ||
            setsCall.registerD != (flip as TwoRegisterInstruction).registerA
        ) {
            continue
        }
        found += factory to sets
    }
    return found.distinctBy { "${it.first}${it.second}" }.singleOrNull()
        ?: refuse("expected the audio toggle to flip the account's audio state once, found ${found.size}")
}

/**
 * The story viewer asks one object a question of the shape `(Integer, boolean, boolean) boolean`,
 * the story sound check, and that method reads the audio state through [factory]. Without both the
 * state isn't what a story plays by, and the patch stops.
 */
private fun BytecodePatchContext.requireViewerReadsIt(factory: MethodReference) {
    val viewer = classDefBy(STORY_VIEWER)
    val checks = viewer.methods.flatMap { method -> method.code().mapNotNull { it.methodReference() } }
        .filter {
            it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Integer;", "Z", "Z")
        }.distinctBy { it.toString() }
    val check = checks.singleOrNull() ?: refuse("expected the story viewer to ask one sound check of the shape (Integer, boolean, boolean), found ${checks.size}")
    val owner = classDefByOrNull(check.definingClass) ?: refuse("the story sound check's class ${check.definingClass} isn't in the app")
    val declared = owner.methods.singleOrNull {
        it.name == check.name && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Integer;", "Z", "Z")
    } ?: refuse("${check.definingClass}->${check.name} isn't declared once")
    val reads = declared.code().any { it.opcode == Opcode.INVOKE_STATIC && it.methodReference()?.toString() == factory.toString() }
    if (!reads) refuse("${check.definingClass}->${check.name}, the story sound check, doesn't read the audio state through $factory")
}

/**
 * Right after the viewer's super call: ask [START_WITH_SOUND] with the viewer, and on a yes take the
 * account's audio state from the viewer's session and set it to on. Anything missing along the way,
 * no yes or no session, goes on to Instagram's own code.
 */
internal fun BytecodePatchContext.applyStorySound(site: StorySoundSite) {
    val (flag, state) = site.scratch
    mutable(site.onCreate).apply {
        addInstructionsWithLabels(
            site.index,
            """
                invoke-static/range { p0 .. p0 }, $START_WITH_SOUND
                move-result v$flag
                if-eqz v$flag, :stock
                invoke-virtual/range { p0 .. p0 }, ${site.session}
                move-result-object v$state
                if-eqz v$state, :stock
                invoke-static { v$state }, ${site.factory}
                move-result-object v$state
                const/4 v$flag, 1
                invoke-virtual { v$state, v$flag }, ${site.setter}
            """,
            ExternalLabel("stock", getInstruction(site.index)),
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

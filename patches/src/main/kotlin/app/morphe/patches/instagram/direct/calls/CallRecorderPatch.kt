/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.feed.confirm.publicInstance
import app.morphe.patches.instagram.feed.confirm.publicStatic
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val RECORDER_PATCH = "Record calls"

internal const val CALL_RECORDER_CLASS = "$EXTENSION_PACKAGE/download/CallRecorder;"
internal const val CALL_AUDIO_SINK = "$EXTENSION_PACKAGE/download/CallAudioSink;"
internal const val AUDIO_API_HOOK = "$CALL_RECORDER_CLASS->audioApi(Ljava/lang/Object;)V"
internal const val CALL_MODEL_HOOK = "$CALL_RECORDER_CLASS->callModel(Ljava/lang/Object;)V"
internal const val SINK_FRAME = "$CALL_AUDIO_SINK->frame(Ljava/lang/Object;)V"

/** The call engine's audio API as Java holds it, a native proxy Instagram keeps by name. */
internal const val AUDIO_API_PROXY = "Lcom/facebook/rsys/audio/gen/AudioApi\$CProxy;"
internal const val NATIVE_HOLDER = "Lcom/facebook/simplejni/NativeHolder;"
internal const val AUDIO_API_HOLDER = "$AUDIO_API_PROXY->mNativeHolder:$NATIVE_HOLDER"

/** One state of a call, which the engine makes anew each time something about the call changes. */
internal const val CALL_MODEL = "Lcom/facebook/rsys/call/gen/CallModel;"
internal const val CALL_PARTICIPANT = "Lcom/facebook/rsys/call/gen/CallParticipant;"

/** What the engine hands a person's decoded sound to. */
internal const val AUDIO_SOURCE_SINK = "Lcom/facebook/rsys/audio/gen/AudioSourceSink;"
internal const val AUDIO_FRAME = "Lcom/facebook/rsys/audio/frame/gen/AudioFrame;"
internal const val ENCODED_PACKET = "Lcom/facebook/rsys/audio/gen/AudioEncodedPacket;"

private const val RECORDER_OBJECT = "Ljava/lang/Object;"
private const val RECORDER_STRING = "Ljava/lang/String;"

/** A method the sink gets so the engine can call it: its name, parameters, registers and body. */
internal class SinkBridge(val name: String, val parameters: List<String>, val registers: Int, val body: String) {
    val shape get() = "(${parameters.joinToString("")})V"
}

/** Every abstract method of [AUDIO_SOURCE_SINK]: a frame goes to the extension, the rest are ignored. */
internal val SINK_BRIDGES = listOf(
    SinkBridge(
        "onFrame", listOf(AUDIO_FRAME), 2,
        """
            invoke-virtual { p0, p1 }, $SINK_FRAME
            return-void
        """,
    ),
    SinkBridge("onEncodedPacket", listOf(ENCODED_PACKET), 2, "return-void"),
    SinkBridge("onPositionUpdate", listOf("F", "F", "F"), 4, "return-void"),
)

/**
 * Adds a record button to the call screen that saves both sides of the call (#732). Included in
 * the default selection with its switch off, so recording is the user's pick.
 */
@Suppress("unused")
val callRecorderPatch = bytecodePatch(
    name = "Record calls",
    description = "Adds a record button to the call screen that saves the call's sound, both sides, to your " +
        "phone. Starts off. Turn it on in HushGram settings > Messages.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("callRecorder")
        recordCalls(findCallRecorder())
        enableStatus("callRecorder")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$RECORDER_PATCH: $why")

/** What [recordCalls] changes, all found before anything does. */
internal class CallRecorderTargets(
    /** The audio API proxy's constructor parameters. */
    val audioApi: List<String>,
    /** The call model's constructor parameters. */
    val callModel: List<String>,
    /** Where the sink's constructor calls Object's, and the register it passes. */
    val superCall: Int,
    val superThis: Int,
)

/**
 * Finds everything before anything changes:
 *
 * - the extension's two hooks and its sink, still an Object with one constructor calling
 *   Object's once, a public frame(Object) and none of the engine's method names;
 * - [AUDIO_SOURCE_SINK], a public abstract class right under Object with a public no-argument
 *   constructor and exactly the abstract methods [SINK_BRIDGES] fills in;
 * - [AUDIO_API_PROXY], with public addSink and removeSink taking (String, int, the sink) and one
 *   constructor from a [NATIVE_HOLDER] that stores it;
 * - [CALL_MODEL]'s one constructor and its public participant fields, and [CALL_PARTICIPANT]'s
 *   public id, username and service flag, which the extension reads by name.
 *
 * Both constructors have to return and keep `this` to every return, and neither may call the
 * extension yet. Anything else is a build this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findCallRecorder(): CallRecorderTargets {
    val recorder = classDefByOrNull(CALL_RECORDER_CLASS) ?: refuse("the extension has no $CALL_RECORDER_CLASS")
    for (hook in listOf(AUDIO_API_HOOK, CALL_MODEL_HOOK)) {
        recorder.publicStatic(hook) ?: refuse("the extension has no public static $hook")
    }

    val sink = classDefByOrNull(CALL_AUDIO_SINK) ?: refuse("the extension has no $CALL_AUDIO_SINK")
    if (sink.superclass != RECORDER_OBJECT) refuse("$CALL_AUDIO_SINK already extends ${sink.superclass}")
    sink.publicInstance("frame", listOf(RECORDER_OBJECT), "V") ?: refuse("$CALL_AUDIO_SINK has no public frame(Object)")
    sink.methods.firstOrNull { method -> SINK_BRIDGES.any { it.name == method.name } }
        ?.let { refuse("$CALL_AUDIO_SINK already has a ${it.name}") }
    val sinkInits = sink.methods.filter { it.name == "<init>" }
    val sinkInit = sinkInits.singleOrNull() ?: refuse("expected $CALL_AUDIO_SINK to have one constructor, found ${sinkInits.size}")
    val sinkCode = sinkInit.recorderCode()
    val superCalls = sinkCode.indices.filter { sinkCode[it].opcode == Opcode.INVOKE_DIRECT && sinkCode[it].calls() == "$RECORDER_OBJECT-><init>()V" }
    val superCall = superCalls.singleOrNull()
        ?: refuse("expected $CALL_AUDIO_SINK's constructor to call Object's once, found ${superCalls.size}")
    val superThis = (sinkCode[superCall] as FiveRegisterInstruction).registerC

    val base = classDefByOrNull(AUDIO_SOURCE_SINK) ?: refuse("this build has no $AUDIO_SOURCE_SINK")
    if (!AccessFlags.PUBLIC.isSet(base.accessFlags) || !AccessFlags.ABSTRACT.isSet(base.accessFlags) ||
        AccessFlags.INTERFACE.isSet(base.accessFlags)
    ) {
        refuse("$AUDIO_SOURCE_SINK isn't a public abstract class")
    }
    if (base.superclass != RECORDER_OBJECT || base.interfaces.isNotEmpty()) {
        refuse("$AUDIO_SOURCE_SINK extends ${base.superclass} and takes ${base.interfaces}, not Object alone")
    }
    base.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags) }
        ?: refuse("$AUDIO_SOURCE_SINK has no public no-argument constructor")
    val asked = base.methods.filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        .map { "${it.name}${it.recorderShape()}" }.sorted()
    val filled = SINK_BRIDGES.map { "${it.name}${it.shape}" }.sorted()
    if (asked != filled) refuse("$AUDIO_SOURCE_SINK asks for $asked, not $filled")

    val api = classDefByOrNull(AUDIO_API_PROXY) ?: refuse("this build has no $AUDIO_API_PROXY")
    for (name in listOf("addSink", "removeSink")) {
        api.publicInstance(name, listOf(RECORDER_STRING, "I", AUDIO_SOURCE_SINK), "V")
            ?: refuse("$AUDIO_API_PROXY has no public $name(String, int, $AUDIO_SOURCE_SINK)")
    }
    val apiInit = api.recorderInit(listOf(NATIVE_HOLDER)) ?: refuse("$AUDIO_API_PROXY has no constructor from $NATIVE_HOLDER")
    if (apiInit.recorderCode().none { it.opcode == Opcode.IPUT_OBJECT && it.field() == AUDIO_API_HOLDER }) {
        refuse("$AUDIO_API_PROXY's constructor doesn't store $AUDIO_API_HOLDER")
    }
    apiInit.requireHookable("$AUDIO_API_PROXY-><init>")

    val model = classDefByOrNull(CALL_MODEL) ?: refuse("this build has no $CALL_MODEL")
    model.requirePublicField("selfParticipant", CALL_PARTICIPANT)
    model.requirePublicField("remoteParticipants", "Ljava/util/ArrayList;", "Ljava/util/List;")
    val modelInits = model.methods.filter { it.name == "<init>" && it.implementation != null }
    val modelInit = modelInits.singleOrNull() ?: refuse("expected $CALL_MODEL to have one constructor, found ${modelInits.size}")
    modelInit.requireHookable("$CALL_MODEL-><init>")

    val participant = classDefByOrNull(CALL_PARTICIPANT) ?: refuse("this build has no $CALL_PARTICIPANT")
    participant.requirePublicField("userId", RECORDER_STRING)
    participant.requirePublicField("username", RECORDER_STRING)
    participant.requirePublicField("isService", "Z")

    return CallRecorderTargets(
        apiInit.parameterTypes.map(Any::toString),
        modelInit.parameterTypes.map(Any::toString),
        superCall,
        superThis,
    )
}

/**
 * Before each return of both constructors, hands the new object to the extension; the hook takes
 * the return's label, so a branch to the return runs it too. Then makes the extension's sink an
 * [AUDIO_SOURCE_SINK]: its superclass, its constructor's super call and the methods the engine
 * calls on it.
 */
internal fun BytecodePatchContext.recordCalls(found: CallRecorderTargets) {
    hookReturns(mutableClassDefBy(AUDIO_API_PROXY), found.audioApi, AUDIO_API_HOOK)
    hookReturns(mutableClassDefBy(CALL_MODEL), found.callModel, CALL_MODEL_HOOK)

    val sink = mutableClassDefBy(CALL_AUDIO_SINK)
    sink.setSuperClass(AUDIO_SOURCE_SINK)
    sink.methods.single { it.name == "<init>" }
        .replaceInstruction(found.superCall, "invoke-direct { v${found.superThis} }, $AUDIO_SOURCE_SINK-><init>()V")
    for (bridge in SINK_BRIDGES) {
        sink.methods.add(
            ImmutableMethod(
                CALL_AUDIO_SINK, bridge.name, bridge.parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value, null, emptySet(),
                ImmutableMethodImplementation(bridge.registers, emptyList(), null, null),
            ).toMutable().apply { addInstructions(0, bridge.body) },
        )
    }
}

private fun hookReturns(owner: MutableClass, parameters: List<String>, hook: String) {
    val constructor = owner.methods.single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == parameters }
    for (at in constructor.recorderReturns().sortedDescending()) {
        constructor.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p0 }, $hook")
    }
}

private fun Method.requireHookable(described: String) {
    if (recorderCode().any { it.calls()?.startsWith(CALL_RECORDER_CLASS) == true }) {
        refuse("$described already calls $CALL_RECORDER_CLASS")
    }
    val returns = recorderReturns()
    if (returns.isEmpty()) refuse("$described never returns")
    requireThisIntact(RECORDER_PATCH, returns)
}

private fun ClassDef.recorderInit(parameters: List<String>): Method? = methods.singleOrNull {
    it.name == "<init>" && it.implementation != null && it.parameterTypes.map(Any::toString) == parameters
}

private fun ClassDef.requirePublicField(name: String, vararg types: String) {
    fields.singleOrNull {
        it.name == name && it.type in types && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$type has no public $name of ${types.joinToString(" or ")}")
}

private fun Method.recorderReturns(): List<Int> {
    val code = recorderCode()
    return code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
}

private fun Instruction.calls(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)
    ?.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }

private fun Instruction.field(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)
    ?.let { "${it.definingClass}->${it.name}:${it.type}" }

private fun Method.recorderShape(): String = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.recorderCode(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

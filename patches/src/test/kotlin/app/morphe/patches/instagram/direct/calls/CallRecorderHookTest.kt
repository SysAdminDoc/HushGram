/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record calls (#732), on each declared build's own dex and on every other build of the same
 * Instagram version: the audio API and every call state go to the extension as the engine makes
 * them, before each return and with Instagram's code kept, and the extension's sink becomes the
 * engine's sink with every method the engine calls on one.
 */
class CallRecorderHookTest {
    @Test
    fun theHooksAndTheSinkAreInTheExtension() {
        val declared = ExtensionDex.classDef(CALL_RECORDER_CLASS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { it.descriptor() }
        for (hook in listOf(AUDIO_API_HOOK, CALL_MODEL_HOOK)) assertTrue("$hook is not in the extension: $declared", hook in declared)
        val sink = ExtensionDex.classDef(CALL_AUDIO_SINK)
        assertEquals("the sink is re-parented by the patch alone", "Ljava/lang/Object;", sink.superclass)
        assertTrue(sink.methods.any {
            it.descriptor() == SINK_FRAME && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        })
    }

    @Test
    fun eachDeclaredBuildHandsTheEngineToTheExtension() = fixtures { bundle -> check(bundle.name, engineClasses(bundle)) }

    @Test
    fun eachOtherBuildDoesToo() {
        for (base in Fixtures.otherBuilds()) check(base.parentFile.name, engineClasses(base))
    }

    @Test
    fun aBranchToTheConstructorsReturnStillReachesTheHook() = fixtures { bundle ->
        val context = PatchContexts.of(engineClasses(bundle).values)
        val init = context.mutableClassDefBy(AUDIO_API_PROXY).methods
            .single { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(NATIVE_HOLDER) }
        val code = init.implementation!!.instructions
        val after = code.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT && (it.reference() as? MethodReference)?.name == "<init>" } + 1
        assertTrue("${bundle.name}: the constructor calls its super", after > 0)
        init.addInstructionsWithLabels(after, "if-eqz p1, :stored", ExternalLabel("stored", code.last { it.opcode == Opcode.RETURN_VOID }))

        context.recordCalls(context.findCallRecorder())

        val patched = init.implementation!!.instructions.toList()
        val target = (patched[after] as BuilderOffsetInstruction).target.location.index
        assertEquals("${bundle.name}: the branch lands on the hook", AUDIO_API_HOOK, patched[target].reference().toString())
        assertEquals("${bundle.name}: and the hook on the return", Opcode.RETURN_VOID, patched[target + 1].opcode)
    }

    @Test
    fun aBuildWithoutTheEnginesSinkFailsThePatch() = refuses("this build has no $AUDIO_SOURCE_SINK") { it.type != AUDIO_SOURCE_SINK }

    @Test
    fun aBuildWithoutTheAudioApiFailsThePatch() = refuses("this build has no $AUDIO_API_PROXY") { it.type != AUDIO_API_PROXY }

    @Test
    fun aBuildWithoutTheCallModelFailsThePatch() = refuses("this build has no $CALL_MODEL") { it.type != CALL_MODEL }

    @Test
    fun aBuildWithoutTheParticipantFailsThePatch() = refuses("this build has no $CALL_PARTICIPANT") { it.type != CALL_PARTICIPANT }

    @Test
    fun anExtensionWithoutTheRecorderFailsThePatch() =
        refuses("the extension has no $CALL_RECORDER_CLASS") { it.type != CALL_RECORDER_CLASS }

    @Test
    fun anExtensionWithoutTheSinkFailsThePatch() = refuses("the extension has no $CALL_AUDIO_SINK") { it.type != CALL_AUDIO_SINK }

    @Test
    fun anEngineSinkAskingForOtherMethodsFailsThePatch() = refusesAfter("$AUDIO_SOURCE_SINK asks for") { context ->
        context.mutableClassDefBy(AUDIO_SOURCE_SINK).methods.removeIf { it.name == "onPositionUpdate" }
    }

    @Test
    fun aCallModelAlreadyHookedFailsThePatch() = refusesAfter("$CALL_MODEL-><init> already calls $CALL_RECORDER_CLASS") { context ->
        val init = context.mutableClassDefBy(CALL_MODEL).methods.single { it.name == "<init>" && it.implementation != null }
        val end = init.implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        init.addInstructions(end, "invoke-static/range { p0 .. p0 }, $CALL_MODEL_HOOK")
    }

    @Test
    fun aSecondRunFailsThePatch() = refusesAfter("$CALL_AUDIO_SINK already extends $AUDIO_SOURCE_SINK") { context ->
        context.recordCalls(context.findCallRecorder())
    }

    /** The patch refuses in its own name for [reason] on each declared build with these classes left out. */
    private fun refuses(reason: String, keep: (ClassDef) -> Boolean) = fixtures { bundle ->
        val context = PatchContexts.of(engineClasses(bundle).values.filter(keep))
        assertRefuses(bundle.name, reason, context)
    }

    /** The patch refuses in its own name for [reason] on each declared build after [change]. */
    private fun refusesAfter(reason: String, change: (BytecodePatchContext) -> Unit) = fixtures { bundle ->
        val context = PatchContexts.of(engineClasses(bundle).values)
        change(context)
        assertRefuses(bundle.name, reason, context)
    }

    private fun assertRefuses(name: String, reason: String, context: BytecodePatchContext) {
        val refusal = assertThrows(PatchException::class.java) { context.findCallRecorder() }
        assertTrue("$name refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        assertTrue("$name refused in another patch's name: ${refusal.message}", refusal.message.orEmpty().startsWith("$RECORDER_PATCH: "))
    }

    private fun check(name: String, classes: Map<String, ClassDef>) {
        for (type in listOf(AUDIO_API_PROXY, CALL_MODEL, CALL_PARTICIPANT, AUDIO_SOURCE_SINK)) {
            assertTrue("$name has no $type", type in classes)
        }
        val context = PatchContexts.of(classes.values)
        val found = context.findCallRecorder()
        assertEquals("$name: the audio API is made from its native holder", listOf(NATIVE_HOLDER), found.audioApi)
        val before = classes.mapValues { (_, classDef) -> classDef.methods.associate { it.descriptor() to kept(it.code()) } }
        val apiInit = context.mutableClassDefBy(AUDIO_API_PROXY).methods.single { it.isInit(found.audioApi) }
        val modelInit = context.mutableClassDefBy(CALL_MODEL).methods.single { it.isInit(found.callModel) }
        val sinkInit = context.mutableClassDefBy(CALL_AUDIO_SINK).methods.single { it.name == "<init>" }
        val changed = setOf(apiInit.descriptor(), modelInit.descriptor(), sinkInit.descriptor())

        context.recordCalls(found)

        assertHooksEveryReturn("$name audio API", apiInit, AUDIO_API_HOOK, before.getValue(AUDIO_API_PROXY).getValue(apiInit.descriptor()))
        assertHooksEveryReturn("$name call model", modelInit, CALL_MODEL_HOOK, before.getValue(CALL_MODEL).getValue(modelInit.descriptor()))

        val sink = context.mutableClassDefBy(CALL_AUDIO_SINK)
        assertEquals("$name: the extension's sink is the engine's", AUDIO_SOURCE_SINK, sink.superclass)
        val initCode = sinkInit.code()
        assertEquals("$name: the sink is made through the engine's constructor", 1,
            initCode.count { it.calls("$AUDIO_SOURCE_SINK-><init>()V") })
        assertFalse("$name: and never Object's", initCode.any { it.calls("Ljava/lang/Object;-><init>()V") })
        assertEquals("$name: the sink's constructor otherwise kept",
            before.getValue(CALL_AUDIO_SINK).getValue(sinkInit.descriptor()).filterNot { it.contains("Ljava/lang/Object;-><init>()V") },
            kept(initCode).filterNot { it.contains("$AUDIO_SOURCE_SINK-><init>()V") })

        for (asked in classes.getValue(AUDIO_SOURCE_SINK).methods.filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) }) {
            val shape = "${asked.name}(${asked.parameterTypes.joinToString("")})${asked.returnType}"
            val bridge = sink.methods.singleOrNull { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == shape }
            assertTrue("$name: the sink has no $shape", bridge != null)
            bridge!!
            assertEquals("$name: $shape is a public instance method", AccessFlags.PUBLIC.value, bridge.accessFlags)
            assertEquals("$name: $shape takes only its own registers", ins(bridge), bridge.implementation!!.registerCount)
            val code = bridge.code()
            if (asked.name == "onFrame") {
                assertEquals("$name: a frame goes to the extension", listOf(Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), code.map { it.opcode })
                assertEquals(SINK_FRAME, code[0].reference().toString())
            } else {
                assertEquals("$name: $shape does nothing", listOf(Opcode.RETURN_VOID), code.map { it.opcode })
            }
        }

        for ((type, methods) in before) {
            if (type == CALL_RECORDER_CLASS) continue
            val now = context.mutableClassDefBy(type).methods.associate { it.descriptor() to kept(it.code()) }
            val added = if (type == CALL_AUDIO_SINK) SINK_BRIDGES.size else 0
            assertEquals("$name: $type lost or gained a method", methods.size + added, now.size)
            for ((method, code) in methods) {
                if (method !in changed) assertEquals("$name: $method changed", code, now[method])
            }
        }
    }

    /** One hook before each return, handing over `this`, nothing branching past it, and the stock code kept around it. */
    private fun assertHooksEveryReturn(what: String, method: Method, hook: String, stock: List<String>) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        assertTrue("$what never returns", returns.isNotEmpty())
        val self = method.implementation!!.registerCount - ins(method)
        for (at in returns) {
            val call = code[at - 1]
            assertEquals("$what: the hook before the return at $at", hook, call.reference().toString())
            assertEquals("$what: a range call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$what: it hands over this", self, (call as RegisterRangeInstruction).startRegister)
            assertEquals("$what: and nothing else", 1, call.registerCount)
        }
        assertEquals("$what: one hook per return", returns.size, code.count { it.calls(hook) })
        for (branch in code.filterIsInstance<BuilderOffsetInstruction>()) {
            assertNotEquals("$what: a branch skips the hook", Opcode.RETURN_VOID, code[branch.target.location.index].opcode)
        }
        assertEquals("$what: Instagram's code kept", stock, kept(code.filterNot { it.calls(hook) }))
    }

    private fun Method.isInit(parameters: List<String>) = name == "<init>" && parameterTypes.map(Any::toString) == parameters

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    /** The registers a method's parameters and `this` take. */
    private fun ins(method: Method) = method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 as Int else 1 } + 1

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.calls(hook: String) = (reference() as? MethodReference)?.toString() == hook

    /** The code as text with alignment nops left out and every goto read as one, so a widened branch still matches. */
    private fun kept(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map { instruction ->
        if (instruction.opcode in setOf(Opcode.GOTO_16, Opcode.GOTO_32)) "${Opcode.GOTO}"
        else instruction.reference()?.let { "${instruction.opcode} $it" } ?: "${instruction.opcode}"
    }

    companion object {
        private val cached = FixtureCache<Map<String, ClassDef>>()

        /** The engine's four classes from the build, and the extension's recorder and sink. */
        private fun engineClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
            val classes = FixtureDex.classes(bundle, setOf(AUDIO_API_PROXY, CALL_MODEL, CALL_PARTICIPANT, AUDIO_SOURCE_SINK)).toMutableMap()
            classes[CALL_RECORDER_CLASS] = ExtensionDex.classDef(CALL_RECORDER_CLASS)
            classes[CALL_AUDIO_SINK] = ExtensionDex.classDef(CALL_AUDIO_SINK)
            classes
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.blend

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Don't report Blend visits: the request for a Blend's reels asks the extension for its visit flag
 * before anything else. Anything the patch can't tell apart fails it before an instruction changes.
 */
class DontReportBlendVisitsHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val quiet = ExtensionDex.classDef(BLEND_VISITS).methods.single {
            it.name == "quiet" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
        }
        assertEquals("Z", quiet.returnType)
        assertTrue(AccessFlags.PUBLIC.isSet(quiet.accessFlags) && AccessFlags.STATIC.isSet(quiet.accessFlags))
        val skips = ExtensionDex.classDef(BLEND_VISITS).methods.single { it.name == "skipsVisitTime" && it.parameterTypes.isEmpty() }
        assertEquals("Z", skips.returnType)
        assertTrue(AccessFlags.PUBLIC.isSet(skips.accessFlags) && AccessFlags.STATIC.isSet(skips.accessFlags))
    }

    /** The visit time's sender asks the extension first and returns on a yes, before its marker or its call (#474). */
    @Test
    fun theVisitTimeAsksBeforeItGoesOut() {
        val context = PatchContexts.of(listOf(repository()))
        val sender = context.findBlendVisitTime()
        val before = sender.code().map(::text)
        skipBlendVisitTime(sender)
        assertSkips("stand-in", sender, before)
        assertEquals("the call stays", SEND.toString(), (sender.code()[5] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun aMissingOrDoubledVisitTimeFailsThePatch() {
        refuses("found 0", listOf(repository(marker = false))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("found 2", listOf(repository(), repository("Lfixture/OtherRepository;"))) { skipBlendVisitTime(findBlendVisitTime()) }
    }

    @Test
    fun aVisitTimeOfAnotherShapeFailsThePatch() {
        refuses("returns Z, not nothing", listOf(repository(answers = true))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("has 0 local register(s), needs 1", listOf(repository(locals = 0))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("jumps back", listOf(repository(loop = true))) { skipBlendVisitTime(findBlendVisitTime()) }
    }

    @Test
    fun theRequestAsksForItsFlagFirst() {
        val context = PatchContexts.of(listOf(api()))
        val request = context.findBlendRequest()
        assertEquals("the flag's register", 4, request.flag)
        quietBlendVisit(request)
        assertHooked("stand-in", request.method, 4)
        val code = request.method.code()
        assertEquals("the endpoint stays", Opcode.CONST_STRING, code[2].opcode)
        assertEquals("the hand-off stays", HAND_OFF.toString(), (code[4] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun aMissingOrDoubledRequestFailsThePatch() {
        refuses("found 0", listOf(api(endpoint = false)))
        refuses("found 2", listOf(api(), api("Lfixture/OtherApi;")))
    }

    @Test
    fun aRequestOfAnotherShapeFailsThePatch() {
        refuses("isn't static", listOf(api(static = false)))
        refuses("takes 2 booleans", listOf(api(flags = 2)))
        refuses("takes 0 booleans", listOf(api(flags = 0)))
        refuses("in 2 calls", listOf(api(handOffs = 2)))
        refuses("in 0 calls", listOf(api(handOffs = 0)))
        refuses("not as a (name, boolean) parameter", listOf(api(handOff = OTHER_HAND_OFF)))
    }

    @Test
    fun aFlagWrittenOverOrAJumpBackFailsThePatch() {
        refuses("writes over parameter 2", listOf(api(overwrite = true)))
        refuses("jumps back", listOf(api(loop = true)))
    }

    /** In each declared build: the one request, asking the extension for its flag first. */
    @Test
    fun eachDeclaredBuildAsksFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksFirstToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.classesLoading(bundle, BLEND_MEDIAS))
        val request = context.findBlendRequest()
        val before = request.method.code().filter { it.opcode != Opcode.NOP }
        assertEquals("$label: the flag is the last parameter", request.method.implementation!!.registerCount - 1, request.flag)
        quietBlendVisit(request)
        assertHooked(label, request.method, request.flag)
        assertEquals("$label: the rest stays", before.map(::text), request.method.code().drop(2).filter { it.opcode != Opcode.NOP }.map(::text))

        val sender = PatchContexts.of(FixtureDex.classesLoading(bundle, BLEND_LAST_VISIT)).findBlendVisitTime()
        val stock = sender.code().filter { it.opcode != Opcode.NOP }.map(::text)
        skipBlendVisitTime(sender)
        assertSkips(label, sender, stock)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(
        reason: String,
        classes: List<ClassDef>,
        patch: BytecodePatchContext.() -> Unit = { quietBlendVisit(findBlendRequest()) },
    ) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.code().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { context.patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.code().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    private fun assertHooked(what: String, method: Method, flag: Int) {
        val code = method.code()
        assertEquals("$what: the call", QUIET_VISIT, (code[0] as ReferenceInstruction).reference.toString())
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$what: the call hands over the flag", listOf(flag), (call.startRegister until call.startRegister + call.registerCount).toList())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer goes back in the flag", flag, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: one call", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == QUIET_VISIT })
    }

    /**
     * [method] starts with the extension's answer in v0, a no going on to the first stock
     * instruction and a yes returning, and the stock code ([before], no padding) follows unchanged.
     */
    private fun assertSkips(what: String, method: Method, before: List<String>) {
        val code = method.code()
        assertEquals("$what: the call", SKIP_VISIT_TIME, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer goes in v0", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the test reads the answer", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: a no goes on to the stock code", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: a yes returns", Opcode.RETURN_VOID, code[3].opcode)
        assertEquals("$what: the rest stays", before, code.drop(4).filter { it.opcode != Opcode.NOP }.map(::text))
        assertEquals("$what: one call", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == SKIP_VISIT_TIME })
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name // opcode-name-ok
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference" // opcode-name-ok
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private companion object {
        const val API = "Lfixture/BlendApi;"
        const val BUILDER = "Lfixture/RequestBuilder;"
        val HAND_OFF = ImmutableMethodReference(BUILDER, "flag", FLAG_PARAMETER, "V")
        val OTHER_HAND_OFF = ImmutableMethodReference(BUILDER, "count", listOf("Ljava/lang/String;", "I"), "V")

        /**
         * Shaped like the Blend request builder: (session, id, flag...) static, two locals, loading the
         * endpoint and a parameter name, then handing the session's builder (v2), the name (v1) and the
         * last parameter to [handOff].
         */
        fun api(
            type: String = API,
            endpoint: Boolean = true,
            static: Boolean = true,
            flags: Int = 1,
            handOffs: Int = 1,
            handOff: ImmutableMethodReference = HAND_OFF,
            overwrite: Boolean = false,
            loop: Boolean = false,
        ): ClassDef {
            val parameters = listOf("Lfixture/Session;", "Ljava/lang/String;") + List(flags) { "Z" }.ifEmpty { listOf("I") }
            val registers = 2 + (if (static) 0 else 1) + parameters.size
            val last = registers - 1
            val code = mutableListOf<ImmutableInstruction>()
            if (endpoint) code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(BLEND_MEDIAS))
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("force_disable_visitation_xma"))
            if (overwrite) code += ImmutableInstruction11n(Opcode.CONST_4, last, 0)
            repeat(handOffs) { code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 1, last, 0, 0, handOff) }
            if (loop) code += ImmutableInstruction10t(Opcode.GOTO, -code.sumOf { it.codeUnits })
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val access = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
            val request = ImmutableMethod(
                type, "request", parameters.map { ImmutableMethodParameter(it, null, null) }, "V", access, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(request))
        }

        const val REPOSITORY = "Lfixture/BlendRepository;"
        val SEND = ImmutableMethodReference(REPOSITORY, "launch", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")

        /**
         * Shaped like the Blend repository's visit time sender: an instance method of (thread, time)
         * loading [BLEND_LAST_VISIT] into v0 first, then handing itself and both parameters on.
         */
        fun repository(
            type: String = REPOSITORY,
            marker: Boolean = true,
            answers: Boolean = false,
            locals: Int = 2,
            loop: Boolean = false,
        ): ClassDef {
            val self = locals
            val code = mutableListOf<ImmutableInstruction>()
            if (marker) code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(BLEND_LAST_VISIT))
            code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, self, self + 1, self + 2, 0, 0, SEND)
            if (loop) code += ImmutableInstruction10t(Opcode.GOTO, -code.sumOf { it.codeUnits })
            code += if (answers) ImmutableInstruction11x(Opcode.RETURN, 0) else ImmutableInstruction10x(Opcode.RETURN_VOID)
            val sender = ImmutableMethod(
                type, "updateLastVisit", SEND.parameterTypes.map { ImmutableMethodParameter(it.toString(), null, null) },
                if (answers) "Z" else "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(locals + 3, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(sender))
        }
    }
}

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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
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

    /**
     * The step building the visit time's request asks the extension right before it loads the
     * request's name and answers that it's done on a yes, before the name or the call (#474).
     */
    @Test
    fun theVisitTimeAsksBeforeItGoesOut() {
        val context = PatchContexts.of(listOf(visit()))
        val site = context.findBlendVisitTime()
        assertEquals("the name's load", 1, site.at)
        assertEquals("the name's register", 2, site.register)
        assertEquals("what it answers when done", UNIT.toString(), site.done.toString())
        val before = site.method.code().map(::text)
        skipBlendVisitTime(site)
        assertSkips("stand-in", site, before)
        assertEquals("the call stays", SEND.toString(), (site.method.code()[7] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun aMissingOrDoubledVisitTimeFailsThePatch() {
        refuses("found 0", listOf(visit(name = false))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("found 2", listOf(visit(), visit("Lfixture/OtherVisit;"))) { skipBlendVisitTime(findBlendVisitTime()) }
    }

    @Test
    fun aVisitTimeOfAnotherShapeFailsThePatch() {
        refuses("returns V, not an object", listOf(visit(answers = false))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("as a literal 2 times, not once", listOf(visit(loads = 2))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("jumps onto", listOf(visit(loop = true))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("answers 0 singletons", listOf(visit(done = 0))) { skipBlendVisitTime(findBlendVisitTime()) }
        refuses("answers 2 singletons", listOf(visit(done = 2))) { skipBlendVisitTime(findBlendVisitTime()) }
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

        val site = PatchContexts.of(FixtureDex.classesLoading(bundle, BLEND_LAST_VISIT)).findBlendVisitTime()
        assertEquals("$label: what it answers when done", UNIT.toString(), site.done.toString())
        val stock = site.method.code().filter { it.opcode != Opcode.NOP }.map(::text)
        skipBlendVisitTime(site)
        assertSkips(label, site, stock)
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
     * Right before the load of the request's name: the extension's answer in the name's register, a
     * no going on to that load and a yes answering what the step answers when it's done. The stock
     * code ([before], no padding) is unchanged around it.
     */
    private fun assertSkips(what: String, site: BlendVisitTime, before: List<String>) {
        val code = site.method.code()
        val at = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == SKIP_VISIT_TIME }
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: the answer goes in the name's register", site.register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[at + 2].opcode)
        assertEquals("$what: the test reads the answer", site.register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: a no goes on to the name's load", at + 5, (code[at + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: a yes reads what the step answers when done", Opcode.SGET_OBJECT, code[at + 3].opcode)
        assertEquals("$what: into the name's register", site.register, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: the field", site.done.toString(), (code[at + 3] as ReferenceInstruction).reference.toString())
        assertEquals("$what: and answers it", Opcode.RETURN_OBJECT, code[at + 4].opcode)
        assertEquals("$what: from the name's register", site.register, (code[at + 4] as OneRegisterInstruction).registerA)
        assertEquals("$what: the name's load", "\"$BLEND_LAST_VISIT\"", text(code[at + 5]))
        val stock = code.filterIndexed { index, _ -> index !in at until at + 5 }.filter { it.opcode != Opcode.NOP }.map(::text)
        assertEquals("$what: the rest stays", before, stock)
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

        const val VISIT = "Lfixture/BlendVisit;"
        val SEND = ImmutableMethodReference(VISIT, "send", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")
        val UNIT = ImmutableFieldReference("Lkotlin/Unit;", "INSTANCE", "Lkotlin/Unit;")
        val SUSPENDED = ImmutableFieldReference("Lfixture/Singletons;", "SUSPENDED", "Lfixture/Singletons;")

        /**
         * Shaped like the coroutine step building the visit time's request: an instance (result)
         * method with three locals loading the Blend's id key into v1 and [BLEND_LAST_VISIT] into
         * v2 ([loads] times), handing both on and answering Kotlin's Unit ([done] singletons read
         * right before a return; a null for none).
         */
        fun visit(
            type: String = VISIT,
            name: Boolean = true,
            loads: Int = 1,
            answers: Boolean = true,
            loop: Boolean = false,
            done: Int = 1,
        ): ClassDef {
            val self = 3
            val code = mutableListOf<ImmutableInstruction>()
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference("blend_id"))
            repeat(if (name) loads else 0) { code += ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(BLEND_LAST_VISIT)) }
            code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, self, 1, 2, 0, 0, SEND)
            if (loop) code += ImmutableInstruction10t(Opcode.GOTO, -code.drop(1).sumOf { it.codeUnits })
            if (!answers) {
                code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            } else {
                if (done == 2) {
                    code += ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, SUSPENDED)
                    code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
                }
                code += if (done == 0) ImmutableInstruction11n(Opcode.CONST_4, 0, 0) else ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, UNIT)
                code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            }
            val step = ImmutableMethod(
                type, "invokeSuspend", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                if (answers) "Ljava/lang/Object;" else "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(self + 2, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(step))
        }
    }
}

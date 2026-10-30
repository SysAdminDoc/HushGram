/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.live

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
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
 * View live anonymously: the live heartbeat manager's tick asks the extension first, right where it
 * asks for a viewer's or guest's heartbeat, passing the live's broadcast id, and returns as if it
 * were done while the switch is on. The live viewer's header hands its root view to the Mark as seen
 * button (#412). Anything the patch can't tell apart fails it before an instruction changes.
 */
class ViewLiveAnonymouslyHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HOLD_LIVE_HEARTBEAT, BIND_LIVE_SEEN_BUTTON)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun theTickAsksFirstAndSkipsToItsPlainReturn() {
        val context = PatchContexts.of(listOf(heartbeat(), manager()))
        val site = context.findViewerHeartbeat()
        assertEquals("the call", 1, site.call)
        assertEquals("the broadcast id, the call's fourth register", 4, site.broadcast)
        assertEquals("the free local", 0, site.register)
        holdViewerHeartbeat(site)
        val tick = context.mutableClassDefBy(MANAGER).methods.single { it.name == "tick" }
        assertHooked("stand-ins", tick.instructions(), tick.implementation!!.instructions.toList(), broadcast = 4)
    }

    @Test
    fun theHeaderHandsItsRootViewToTheButtonLast() {
        val context = PatchContexts.of(listOf(header(), header(HOST, menu = false)))
        val site = context.findLiveHeader()
        assertEquals(HEADER, site.constructor.definingClass)
        assertEquals("the return", 5, site.end)
        bindLiveSeenButton(site)
        assertBound("stand-ins", context.mutableClassDefBy(HEADER).methods.single { it.name == "<init>" }.instructions(), 5, 3)
        assertEquals("the host's header stays", header(HOST, menu = false).methods.single().instructions().map(::text),
            context.mutableClassDefBy(HOST).methods.single().instructions().map(::text))
    }

    @Test
    fun aMissingDoubledOrUnsafeHeaderFailsThePatch() {
        refusesHeader("found 0", listOf(header(menu = false)))
        refusesHeader("found 0", listOf(header(close = false)))
        refusesHeader("found 0", listOf(header(rootFirst = false)))
        refusesHeader("found 2", listOf(header(), header(HOST)))
        refusesHeader("one return in the live header constructor, found 2", listOf(header(returns = 2)))
        refusesHeader("writes over parameter 0 (v3)", listOf(header(reusesRoot = true)))
    }

    @Test
    fun aMissingOrDoubledPieceFailsThePatch() {
        refuses("viewer heartbeat", listOf(manager()))
        refuses("viewer heartbeat", listOf(heartbeat(), heartbeat("Lfixture/OtherHeartbeat;"), manager()))
        refuses("is missing", listOf(heartbeat()))
        refuses("found 0", listOf(heartbeat(), manager(calls = 0)))
        refuses("found 2", listOf(heartbeat(), manager(calls = 2)))
        refuses("found 2", listOf(heartbeat(), manager(secondTick = true)))
        refuses("plain return in the heartbeat tick, found 0", listOf(heartbeat(), manager(plainReturns = 0)))
        refuses("plain return in the heartbeat tick, found 2", listOf(heartbeat(), manager(plainReturns = 2)))
    }

    @Test
    fun aJumpToTheCallOrNoFreeLocalFailsThePatch() {
        refuses("jumps to the tick's viewer heartbeat call", listOf(heartbeat(), manager(jump = true)))
        refuses("needs 1", listOf(heartbeat(), manager(busy = true)))
    }

    /**
     * In each declared build: the manager's one tick, asking the extension with the broadcast id right
     * before the viewer's heartbeat, and the live viewer's header binding the button.
     */
    @Test
    fun eachDeclaredBuildHoldsTheViewerHeartbeat() {
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

    @Test
    fun eachOtherBuildHoldsTheViewerHeartbeatToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = (FixtureDex.classesHolding(bundle, LIVE_WITH_ELIGIBILITY) +
            FixtureDex.classes(bundle, setOf(LIVE_HEARTBEAT_MANAGER)).values).distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val site = context.findViewerHeartbeat()
        val original = site.tick.instructions()[site.call]
        val broadcast = when (original) {
            is FiveRegisterInstruction -> original.registerF
            else -> (original as RegisterRangeInstruction).startRegister + 3
        }
        holdViewerHeartbeat(site)
        val code = site.tick.instructions()
        assertHooked(label, code, site.tick.implementation!!.instructions.toList(), broadcast, site.call)
        assertEquals("${label}: the heartbeat call stays", text(original), text(code[site.call + 3]))
        val calls = context.mutableClassDefBy(LIVE_HEARTBEAT_MANAGER).methods.sumOf { method ->
            method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == HOLD_LIVE_HEARTBEAT }
        }
        assertEquals("${label}: hold calls in the manager", 1, calls)
        bindsTheButton(bundle, label)
    }

    /** Every class loading the menu's id, as the patcher's literal index would pick them, and the one header binding the button. */
    private fun bindsTheButton(bundle: File, label: String) {
        val loading = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == LIVE_HEADER_OPTIONS } == true
                }
                if (loads) loading += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(loading)
        val site = context.findLiveHeader()
        val root = site.constructor.parameterRegisterNumber(0)
        bindLiveSeenButton(site)
        assertBound(label, site.constructor.instructions(), site.end, root)
        val binds = loading.sumOf { classDef ->
            context.mutableClassDefBy(classDef.type).methods.sumOf { method ->
                method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == BIND_LIVE_SEEN_BUTTON }
            }
        }
        assertEquals("${label}: button binds", 1, binds)
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { holdViewerHeartbeat(context.findViewerHeartbeat()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The header patch refuses for the reason given, and nothing has changed. */
    private fun refusesHeader(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) { bindLiveSeenButton(context.findLiveHeader()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The constructor's last act before its return: the root view, still in [root], goes to the button. */
    private fun assertBound(what: String, code: List<Instruction>, end: Int, root: Int) {
        val bind = code[end]
        assertEquals("$what: the bind", Opcode.INVOKE_STATIC_RANGE, bind.opcode)
        assertEquals("$what: the bind", BIND_LIVE_SEEN_BUTTON, (bind as ReferenceInstruction).reference.toString())
        assertEquals("$what: the root view", root, (bind as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, bind.registerCount)
        assertEquals("$what: then the return", Opcode.RETURN_VOID, code[end + 1].opcode)
        assertEquals("$what: one return", 1, code.count { it.opcode == Opcode.RETURN_VOID })
    }

    /** The guard sits right before the heartbeat call, reads the broadcast id, and jumps to the tick's plain return. */
    private fun assertHooked(what: String, code: List<Instruction>, built: List<Instruction>, broadcast: Int, call: Int = 1) {
        assertEquals("$what: the guard", HOLD_LIVE_HEARTBEAT, (code[call] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the guard's form", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
        assertEquals("$what: the broadcast id", broadcast, (code[call] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, (code[call] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[call + 1].opcode)
        assertEquals("$what: the branch", Opcode.IF_NEZ, code[call + 2].opcode)
        assertTrue("$what: the heartbeat call follows",
            ((code[call + 3] as ReferenceInstruction).reference as MethodReference).parameterTypes.size == 4)
        val target = (built[call + 2] as BuilderOffsetInstruction).target.location.index
        val load = code[target]
        assertEquals("$what: the branch lands on the plain return's load", Opcode.SGET_OBJECT, load.opcode)
        val field = (load as ReferenceInstruction).reference as FieldReference
        assertEquals("$what: a singleton", field.definingClass, field.type)
        assertEquals("$what: returned right away", Opcode.RETURN_OBJECT, code[target + 1].opcode)
        assertEquals("$what: the same register", (load as OneRegisterInstruction).registerA, (code[target + 1] as OneRegisterInstruction).registerA)
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private companion object {
        const val HEARTBEAT = "Lfixture/ViewerHeartbeat;"
        const val MANAGER = LIVE_HEARTBEAT_MANAGER
        const val UNIT = "Lfixture/Unit;"
        const val HEADER = "Lfixture/LiveHeader;"
        const val HOST = "Lfixture/HostHeader;"
        val BEAT_PARAMETERS = listOf("Lfixture/Tick;", INTEGER, STRING, "Lfixture/Continuation;")
        val FIND_VIEW = ImmutableMethodReference(VIEW, "findViewById", listOf("I"), VIEW)

        /**
         * Shaped like the live header's constructor, (root view, session): it looks up the menu and
         * the close button under the root, in v3, and returns. [menu] and [close] drop a lookup,
         * [rootFirst] moves the root to the second parameter, [returns] adds a branch to a second
         * return, and [reusesRoot] puts the session in the root's register before returning.
         */
        fun header(type: String = HEADER, menu: Boolean = true, close: Boolean = true, rootFirst: Boolean = true,
                   returns: Int = 1, reusesRoot: Boolean = false): ClassDef {
            val root = if (rootFirst) 3 else 4
            val code = mutableListOf<ImmutableInstruction>(
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 2, 0, 0, 0, 0,
                    ImmutableMethodReference(OBJECT, "<init>", emptyList(), "V")),
            )
            for ((wanted, id) in listOf(menu to LIVE_HEADER_OPTIONS, close to LIVE_HEADER_CLOSE)) {
                if (!wanted) continue
                code += ImmutableInstruction31i(Opcode.CONST, 0, id)
                code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, root, 0, 0, 0, 0, FIND_VIEW)
            }
            if (reusesRoot) code += ImmutableInstruction12x(Opcode.MOVE_OBJECT, 3, 4)
            if (returns == 2) code += ImmutableInstruction21t(Opcode.IF_EQZ, 4, 3)
            repeat(returns) { code += ImmutableInstruction10x(Opcode.RETURN_VOID) }
            val parameters = if (rootFirst) listOf(VIEW, "Lfixture/Session;") else listOf("Lfixture/Session;", VIEW)
            val init = method(type, "<init>", parameters, "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, 5, code)
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, listOf(init))
        }

        fun method(type: String, name: String, parameters: List<String>, returnType: String, access: Int, registers: Int,
                   code: List<ImmutableInstruction>) = ImmutableMethod(type, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, access, null, null,
            ImmutableMethodImplementation(registers, code, null, null))

        /** Shaped like the viewer's heartbeat: an instance (tick, role, broadcast id, continuation) method adding live_with_eligibility. */
        fun heartbeat(type: String = HEARTBEAT): ClassDef {
            val beat = method(type, "beat", BEAT_PARAMETERS, OBJECT, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, 6,
                listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(LIVE_WITH_ELIGIBILITY)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ))
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, listOf(beat))
        }

        /**
         * Shaped like the heartbeat manager: a static tick that loads a local, asks for the viewer's
         * heartbeat with v1 to v5 (v0 to v4 when [busy], leaving no local free), keeps the answer, and
         * returns a singleton. [jump] makes its first instruction a branch onto the call.
         */
        fun manager(calls: Int = 1, secondTick: Boolean = false, plainReturns: Int = 1, jump: Boolean = false,
                    busy: Boolean = false): ClassDef {
            val beat = ImmutableMethodReference(HEARTBEAT, "beat", BEAT_PARAMETERS, OBJECT)
            val unit = ImmutableFieldReference(UNIT, "INSTANCE", UNIT)
            val first = if (busy) 0 else 1
            fun tick(name: String): ImmutableMethod {
                val code = mutableListOf<ImmutableInstruction>(
                    if (jump) ImmutableInstruction21t(Opcode.IF_EQZ, 6, 2) else ImmutableInstruction11n(Opcode.CONST_4, 6, 0),
                )
                repeat(calls) {
                    code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 5, first, first + 1, first + 2, first + 3, first + 4, beat)
                    code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, first)
                }
                if (calls == 0) code += ImmutableInstruction10x(Opcode.NOP)
                repeat(plainReturns) {
                    code += ImmutableInstruction21c(Opcode.SGET_OBJECT, first, unit)
                    code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, first)
                }
                if (plainReturns == 0) code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, first)
                val locals = if (busy) 5 else 7
                return method(MANAGER, name, listOf("Lfixture/Tick;", MANAGER, "Lfixture/Continuation;"), OBJECT,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, locals + 3, code)
            }
            val methods = listOf(tick("tick")) + if (secondTick) listOf(tick("tickAgain")) else emptyList()
            return ImmutableClassDef(MANAGER, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, methods)
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.loop

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.media.taptoplay.CLIPS_VIEWER_CONFIG
import app.morphe.patches.instagram.media.taptoplay.PLAY_INTERNAL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StopVideoLoopingPatchTest {
    private val player = "Lfixture/VideoPlayer;"
    private val groot = "Lfixture/GrootPlayer;"
    private val core = "Lfixture/HeavisideManager;"
    private val logger = "Lfixture/VideoLogger;"
    private val reelsLogger = "Lfixture/ClipsVideoLogger;"
    private val string = "Ljava/lang/String;"
    private val objectType = "Ljava/lang/Object;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val setLooping = "$groot->A0d(Z)V"

    /**
     * playInternal and the player's setter each hand the engine's setLooping the loop flag through
     * the hook first, with the player, and the engine reads the hook's answer from the flag's own
     * register. A branch onto the call passes through the hook. The engine's other (boolean) method,
     * which never reaches the core, is left alone.
     */
    @Test
    fun eachLoopFlagGoesThroughTheHook() {
        val context = PatchContexts.of(classes())
        assertNull(context.stopVideoLooping())

        val internal = context.method(player, "A0I").code()
        val hook = internal.indexOfFirst { it.referenceText() == LOOP }
        assertEquals("playInternal hooks once", 1, internal.count { it.referenceText() == LOOP })
        assertEquals("with the player and the flag", listOf(2, 1), internal[hook].registers())
        assertEquals("the answer goes back in the flag", 1, (internal[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals(setLooping, internal[hook + 2].referenceText())
        assertEquals("the engine reads the answer", listOf(0, 1), internal[hook + 2].registers())
        val branch = internal.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        assertEquals("the branch onto the call runs the hook", hook, internal.target(branch))

        val setter = context.method(player, "A0i").code()
        val setterHook = setter.indexOfFirst { it.referenceText() == LOOP }
        assertEquals("with this and the flag", listOf(1, 2), setter[setterHook].registers())
        assertEquals(setLooping, setter[setterHook + 2].referenceText())
        assertEquals("the setter keeps Instagram's flag for itself", Opcode.IPUT_BOOLEAN, setter.first().opcode)

        assertTrue("the other (boolean) call stays as it was", context.method(player, "A0b").code().none { it.referenceText() == LOOP })
    }

    /** StopLooping.reels answers whether a player was made with the Reels viewer's video logger. */
    @Test
    fun reelsReadsThePlayersLogger() {
        val context = PatchContexts.of(classes())
        assertNull(context.stopVideoLooping())
        val reels = context.method(STOP_LOOPING, "reels").code()
        assertEquals(
            "a player, cast, its logger, the Reels viewer's",
            listOf(player, player, "$player->logger:$logger", reelsLogger),
            reels.mapNotNull { it.referenceText() },
        )
        assertEquals(Opcode.RETURN, reels.last().opcode)
    }

    /** Each shape the patch can't place the hook in safely leaves every class as it was and says why. */
    @Test
    fun refusesAndChangesNothing() {
        for ((variant, why) in listOf(
            Variant(coreString = false) to "no instance (boolean)void loads",
            Variant(secondWrapper = true) to "expected one (boolean)void",
            Variant(twoCallsInPlayInternal = true) to "2 times, expected once",
            Variant(flagReadAfter = true) to "reads the loop flag",
            Variant(playerOverwritten = true) to "writes over parameter 0",
            Variant(hiddenLogger = true) to "not public",
            Variant(rangeCall = true) to "expected a plain invoke",
        )) {
            val context = PatchContexts.of(classes(variant))
            val reason = context.stopVideoLooping()
            assertTrue("$variant: $reason", reason != null && why in reason)
            for (method in context.classDefBy(player).methods) {
                assertTrue("$variant: ${method.name} changed", method.code().none { it.referenceText() == LOOP })
            }
            assertEquals("$variant: the stub was filled", 2, context.method(STOP_LOOPING, "reels").code().size)
        }
    }

    /**
     * In each declared build the engine's setLooping is found and IgVideoPlayerImpl calls it twice,
     * in playInternal and in its setter, and both calls get the hook.
     */
    @Test
    fun eachDeclaredBuildHooksBothCalls() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The same on every other build of the version. */
    @Test
    fun eachOtherBuildHooksBothCallsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val players = FixtureDex.classesHolding(bundle, PLAY_INTERNAL)
        // The engines the player calls, and the core holding the setLooping log line.
        val engines = FixtureDex.classes(
            bundle,
            players.flatMap { it.methods }.flatMap { it.code() }.mapNotNull { it.methodReference() }
                .filter { it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Z") }.map { it.definingClass }.toSet(),
        ).values.toList()
        val madeWithConfig = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            dex.classes.filterTo(madeWithConfig) { classDef ->
                classDef.methods.any { it.name == "<init>" && it.parameterTypes.firstOrNull() == CLIPS_VIEWER_CONFIG }
            }
        }
        // classesLoading carries the pools Redex moved the string into.
        val cores = FixtureDex.classesLoading(bundle, SET_LOOPING)
        val classes = (players + engines + madeWithConfig + cores + ExtensionDex.classDef(STOP_LOOPING))
            .distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val sites = context.findLoopSites()
        assertEquals("$label: the player", players.single().type, sites.player)
        assertEquals("$label: the methods calling setLooping", 2, sites.sites.size)
        assertEquals("$label: once each", listOf(1, 1), sites.sites.map { it.second.size })
        assertTrue("$label: one is static, playInternal", sites.sites.any { AccessFlags.STATIC.isSet(it.first.accessFlags) })
        assertTrue("$label: one is the setter", sites.sites.any { !AccessFlags.STATIC.isSet(it.first.accessFlags) && it.first.parameterTypes.map(Any::toString) == listOf("Z") })

        assertNull("$label", context.stopVideoLooping())
        var hooks = 0
        for (method in context.classDefBy(sites.player).methods) {
            val code = method.code()
            code.forEachIndexed { index, instruction ->
                if (instruction.referenceText() != LOOP) return@forEachIndexed
                hooks++
                val flag = instruction.registers()[1]
                assertEquals("$label ${method.name}: the answer goes back in the flag", flag, (code[index + 1] as OneRegisterInstruction).registerA)
                assertEquals("$label ${method.name}: then the engine's setLooping", sites.setLooping, code[index + 2].methodReference()?.key())
                assertEquals("$label ${method.name}: with the answer", flag, code[index + 2].registers()[1])
            }
        }
        assertEquals("$label: hooks", 2, hooks)
    }

    private data class Variant(
        val coreString: Boolean = true,
        val secondWrapper: Boolean = false,
        val twoCallsInPlayInternal: Boolean = false,
        val flagReadAfter: Boolean = false,
        val playerOverwritten: Boolean = false,
        val hiddenLogger: Boolean = false,
        val rangeCall: Boolean = false,
    )

    private fun classes(v: Variant = Variant()): List<ClassDef> {
        val call = if (v.rangeCall) "invoke-virtual/range { v0 .. v1 }, $setLooping" else "invoke-virtual { v0, v1 }, $setLooping"
        // playInternal: static (player, reason, playAfterSeek, fromPrepare), two locals, so p0 is v2.
        val internal = method(player, "A0I", listOf(player, string, "Z", "Z"), "V", 6, static = true, body = """
            const-string v0, "$PLAY_INTERNAL"
            iget-object v0, p0, $player->engine:$groot
            if-eqz v0, :end
            iget-boolean v1, p0, $player->looping:Z
            ${if (v.playerOverwritten) "move-object p0, v0" else ""}
            if-nez p2, :call
            const/4 v1, 0x0
            :call
            $call
            ${if (v.twoCallsInPlayInternal) "invoke-virtual { v0, v1 }, $setLooping" else ""}
            :end
            return-void
        """)
        // The setter: this is v1, the flag p1 is v2.
        val setter = method(player, "A0i", listOf("Z"), "V", 3, static = false, body = """
            iput-boolean p1, p0, $player->looping:Z
            iget-object v0, p0, $player->engine:$groot
            if-eqz v0, :done
            invoke-virtual { v0, p1 }, $setLooping
            ${if (v.flagReadAfter) "iput-boolean p1, p0, $player->looping:Z" else ""}
            :done
            return-void
        """)
        val other = method(player, "A0b", listOf("Z"), "V", 3, static = false, body = """
            iget-object v0, p0, $player->engine:$groot
            invoke-virtual { v0, p1 }, $groot->A0b(Z)V
            return-void
        """)
        val loggerFlags = if (v.hiddenLogger) AccessFlags.FINAL.value else AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val videoPlayer = classDef(
            player, listOf(internal, setter, other),
            listOf(
                ImmutableField(player, "engine", groot, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(player, "looping", "Z", AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(player, "logger", logger, loggerFlags, null, null, null),
            ),
        )
        val engine = classDef(
            groot,
            listOf(
                method(groot, "A0d", listOf("Z"), "V", 3, static = false, body = """
                    iget-object v0, p0, $groot->core:$core
                    invoke-virtual { v0, p1 }, $core->A0W(Z)V
                    return-void
                """),
                method(groot, "A0b", listOf("Z"), "V", 3, static = false, body = if (v.secondWrapper) """
                    iget-object v0, p0, $groot->core:$core
                    invoke-virtual { v0, p1 }, $core->A0W(Z)V
                    return-void
                """ else """
                    return-void
                """),
            ),
            listOf(ImmutableField(groot, "core", core, AccessFlags.PUBLIC.value, null, null, null)),
        )
        val heaviside = classDef(core, listOf(method(core, "A0W", listOf("Z"), "V", 3, static = false, body = """
            const-string v0, "${if (v.coreString) SET_LOOPING else "setMuted: %s"}"
            return-void
        """)))
        val loggers = listOf(
            classDef(logger, emptyList()),
            classDef(
                reelsLogger,
                listOf(
                    method(reelsLogger, "<init>", listOf(CLIPS_VIEWER_CONFIG, session), "V", 3, static = false, body = """
                        invoke-direct { p0 }, $logger-><init>()V
                        iput-object p1, p0, $reelsLogger->config:$CLIPS_VIEWER_CONFIG
                        return-void
                    """),
                ),
                listOf(ImmutableField(reelsLogger, "config", CLIPS_VIEWER_CONFIG, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
                superclass = logger,
            ),
        )
        return listOf(videoPlayer, engine, heaviside) + loggers + ExtensionDex.classDef(STOP_LOOPING)
    }

    private fun BytecodePatchContext.method(type: String, name: String): Method = classDefBy(type).methods.first { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.methodReference() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Instruction.registers(): List<Int> {
        val call = this as FiveRegisterInstruction
        return listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean, body: String): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), superclass: String = objectType): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, fields, methods)
}

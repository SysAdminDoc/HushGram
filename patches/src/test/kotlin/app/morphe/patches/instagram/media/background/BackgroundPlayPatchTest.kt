/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.media.resume.STOPPED
import app.morphe.patches.instagram.media.taptoplay.ALLOW_START
import app.morphe.patches.instagram.media.taptoplay.PLAY_INTERNAL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BackgroundPlayPatchTest {
    private val player = "Lfixture/VideoPlayer;"
    private val groot = "Lfixture/GrootPlayer;"
    private val activity = "Lcom/instagram/base/activity/IgFragmentActivity;"
    private val string = "Ljava/lang/String;"
    private val objectType = "Ljava/lang/Object;"
    private val playInternal = "$player->A0I($player${string}ZZ)V"

    /**
     * The pause and the stop ask the hold hook first, with the player and the reason, and return
     * when it says yes; a no lands on the method's own first instruction. The release, playInternal,
     * the volume setter and the leave hint each tell the extension first thing, with their own
     * registers. The other (String) and (String, boolean) methods are left alone.
     */
    @Test
    fun eachHookGoesFirst() {
        val context = PatchContexts.of(classes())
        context.keepPlayingInBackground(context.findBackgroundPlaySites())

        for ((name, first) in listOf("A0Z" to Opcode.CONST_STRING, "A0d" to Opcode.CONST_STRING)) {
            val code = context.method(player, name).code()
            assertEquals("$name asks first", HOLD, code[0].referenceText())
            assertEquals("$name: this and the reason", listOf(1, 2), code[0].rangeRegisters())
            assertEquals("$name: the answer in v0", 0, (code[1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, code[2].opcode)
            assertEquals("$name: a yes returns", Opcode.RETURN_VOID, code[3].opcode)
            assertEquals("$name: a no runs Instagram's own code", 4, code.target(2))
            assertEquals(first, code[4].opcode)
        }
        assertEquals(RELEASED, context.method(player, "A0a").code()[0].referenceText())
        assertEquals("the release hands this", listOf(1), context.method(player, "A0a").code()[0].rangeRegisters())
        assertEquals(STARTED_PLAYING, context.method(player, "A0I").code()[0].referenceText())
        assertEquals("playInternal hands its player", listOf(1), context.method(player, "A0I").code()[0].rangeRegisters())
        assertEquals(VOLUME, context.method(player, "A0S").code()[0].referenceText())
        assertEquals("the setter hands this and the volume asked for", listOf(1, 2), context.method(player, "A0S").code()[0].rangeRegisters())
        assertEquals(LEAVING, context.method(activity, "onUserLeaveHint").code()[0].referenceText())

        for (name in listOf("A0b", "Ga8", "A0c", "A0o")) {
            assertTrue("$name stays as it was", context.method(player, name).code().none { it.referenceText()?.startsWith(BACKGROUND_PLAY) == true })
        }
    }

    /**
     * Resume long videos' stopped hook stays first in the pause and the stop, and Tap to play's start
     * guard first in playInternal, whichever patch runs first: the hold guard and the started call go
     * in right after them, the hold guard's no still lands on Instagram's own code, and Tap to play's
     * allowed path runs the started call.
     */
    @Test
    fun theOtherPatchesHooksStayFirstWhicheverRunsFirst() {
        val othersFirst = PatchContexts.of(classes()).apply {
            otherPatchesHooks()
            keepPlayingInBackground(findBackgroundPlaySites())
        }
        val oursFirst = PatchContexts.of(classes()).apply {
            keepPlayingInBackground(findBackgroundPlaySites())
            otherPatchesHooks()
        }
        for ((order, context) in listOf("others first" to othersFirst, "ours first" to oursFirst)) {
            for (name in listOf("A0Z", "A0d")) {
                val code = context.method(player, name).code()
                assertEquals("$order $name: Resume's hook first", STOPPED, code[0].referenceText())
                assertEquals("$order $name: then the hold", HOLD, code[1].referenceText())
                assertEquals("$order $name: this and the reason", listOf(1, 2), code[1].rangeRegisters())
                assertEquals(Opcode.IF_EQZ, code[3].opcode)
                assertEquals("$order $name: a yes returns", Opcode.RETURN_VOID, code[4].opcode)
                assertEquals("$order $name: a no runs Instagram's own code", 5, code.target(3))
                assertEquals(Opcode.CONST_STRING, code[5].opcode)
            }
            val internal = context.method(player, "A0I").code()
            assertEquals("$order: Tap to play's guard first", ALLOW_START, internal[2].referenceText())
            assertEquals(Opcode.IF_NEZ, internal[4].opcode)
            assertEquals("$order: then the started call", STARTED_PLAYING, internal[6].referenceText())
            assertEquals("$order: an allowed start runs it", 6, internal.target(4))
            assertEquals(Opcode.CONST_STRING, internal[7].opcode)
        }
        for (name in listOf("A0Z", "A0d", "A0I")) {
            assertEquals(
                "$name: the same code either way",
                othersFirst.method(player, name).code().map { it.opcode to it.referenceText() },
                oursFirst.method(player, name).code().map { it.opcode to it.referenceText() },
            )
        }
    }

    /** What Resume long videos and Tap to play put first in the player's methods, the way they do it. */
    private fun BytecodePatchContext.otherPatchesHooks() {
        val methods = mutableClassDefBy(player).methods
        for (name in listOf("A0Z", "A0d")) {
            methods.single { it.name == name }.addInstructions(0, "invoke-static/range { p0 .. p1 }, $STOPPED")
        }
        methods.single { it.name == "A0I" }.apply {
            addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p0, $player->A0F:$groot
                    instance-of v0, v0, $groot
                    invoke-static { p0, p1, v0 }, $ALLOW_START
                    move-result v0
                    if-nez v0, :start
                    return-void
                """,
                ExternalLabel("start", getInstruction(0)),
            )
        }
    }

    /** The stubs check for a player, cast it, and call its own methods or read its engine. */
    @Test
    fun theStubsReachThePlayer() {
        val context = PatchContexts.of(classes())
        context.keepPlayingInBackground(context.findBackgroundPlaySites())

        assertEquals(listOf(player, player, "$player->A0o()Z"), context.references("playing"))
        assertEquals(listOf(player, player, FRAGMENT_PAUSED, "$player->A0Z(${string})V"), context.references("pause"))
        assertEquals(listOf(player, player, RESUME, "$player->A0c(${string}Z)V"), context.references("play"))
        assertEquals(listOf(player, player, "$player->A0F:$groot"), context.references("engine"))
        assertEquals(Opcode.RETURN_OBJECT, context.method(BACKGROUND_PLAY, "engine").code().last().opcode)
    }

    /** Each shape the patch can't work with stops it before anything changes, saying what's missing. */
    @Test
    fun refusesAndChangesNothing() {
        for ((variant, why) in listOf(
            Variant(hiddenIsPlaying = true) to "not public",
            Variant(secondPause = true) to "expected a pause",
            Variant(clampedOnce = true) to "expected a volume setter",
            Variant(leaveHint = false) to "has no onUserLeaveHint",
            Variant(secondEngineField = true) to "expected one $groot field",
            Variant(stopAsks = false) to "both its pause and its stop ask",
        )) {
            val context = PatchContexts.of(classes(variant))
            try {
                context.findBackgroundPlaySites()
                fail("$variant: found sites")
            } catch (refused: PatchException) {
                assertTrue("$variant: ${refused.message}", refused.message.orEmpty().contains(why))
            }
            for (method in context.classDefBy(player).methods) {
                assertTrue("$variant: ${method.name} changed", method.code().none { it.referenceText()?.startsWith(BACKGROUND_PLAY) == true })
            }
        }
    }

    /** On each declared build the player's methods are found and every hook goes in first. */
    @Test
    fun eachDeclaredBuildTakesTheHooks() {
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
    fun eachOtherBuildTakesTheHooksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val players = FixtureDex.classesHolding(bundle, PLAY_INTERNAL)
        val activities = FixtureDex.classes(bundle, setOf(activity)).values
        // withStringPools carries the pools Redex moved the reasons into.
        val classes = (FixtureDex.withStringPools(bundle, players) + activities + ExtensionDex.classDef(BACKGROUND_PLAY))
            .distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val sites = context.findBackgroundPlaySites()
        assertEquals("$label: the player", players.single().type, sites.player)
        if ("386014758" in label) {
            assertEquals(
                "$label: Instagram's names",
                listOf("A0Z", "A0d", "A0a", "A0c", "A0o", "A0S", "${sites.player}->A0F:LX/04Ul;"),
                listOf(sites.pause.name, sites.stop.name, sites.release.name, sites.play.name, sites.isPlaying.name, sites.setVolume.name, sites.engine),
            )
        }

        context.keepPlayingInBackground(sites)
        for ((method, hook) in listOf(
            sites.pause to HOLD, sites.stop to HOLD, sites.release to RELEASED, sites.playInternal to STARTED_PLAYING,
            sites.setVolume to VOLUME, sites.leaveHint to LEAVING,
        )) {
            val code = context.method(method.definingClass, method.name, method.parameterTypes.map(Any::toString)).code()
            assertEquals("$label ${method.name}: the hook goes first", hook, code[0].referenceText())
            assertEquals("$label ${method.name}: and only there", 1, code.count { it.referenceText() == hook })
            if (hook == HOLD) assertEquals("$label ${method.name}: a no runs Instagram's own code", 4, code.target(2))
        }
        assertEquals("$label: playing reads the player's own", "${sites.player}->${sites.isPlaying.name}()Z", context.references("playing").last())
    }

    private data class Variant(
        val hiddenIsPlaying: Boolean = false,
        val secondPause: Boolean = false,
        val clampedOnce: Boolean = false,
        val leaveHint: Boolean = true,
        val secondEngineField: Boolean = false,
        val stopAsks: Boolean = true,
    )

    private fun classes(v: Variant = Variant()): List<ClassDef> {
        // playInternal: static (player, reason, playAfterSeek, fromPrepare), one local, so p0 is v1.
        val internal = method("A0I", listOf(player, string, "Z", "Z"), "V", 5, static = true, body = """
            const-string v0, "$PLAY_INTERNAL"
            return-void
        """)
        // The pause: this is v1, the reason v2. It hands the reason to the engine's own pause.
        val pause = method("A0Z", listOf(string), "V", 3, body = """
            const-string v0, "$FRAGMENT_PAUSED"
            invoke-virtual { p1, v0 }, $string->equals($objectType)Z
            move-result v0
            if-nez v0, :pause
            const-string v0, "$SINGLE_TAP_PAUSED"
            :pause
            invoke-virtual { p0 }, $player->A0o()Z
            move-result v0
            if-eqz v0, :done
            iget-object v0, p0, $player->A0F:$groot
            invoke-virtual { v0, p1 }, $groot->A0Z($string)V
            :done
            return-void
        """)
        val stop = method("A0d", listOf(string, "Z"), "V", 4, body = """
            const-string v0, "$UI_THREAD"
            invoke-static { v0 }, Lfixture/Threads;->check($string)V
            ${if (v.stopAsks) "invoke-virtual { p0 }, $player->A0o()Z" else ""}
            const-string v0, "$FRAGMENT_PAUSED"
            return-void
        """)
        val release = method("A0a", listOf(string), "V", 3, body = """
            const/4 v0, 0x0
            invoke-virtual { p0, p1, v0 }, $player->A0d(${string}Z)V
            return-void
        """)
        val other = method("A0b", listOf(string), "V", 3, body = """
            iget-object v0, p0, $player->A0F:$groot
            invoke-virtual { v0, p1 }, $groot->A0b($string)V
            return-void
        """)
        val play = method("A0c", listOf(string, "Z"), "V", 5, body = """
            const-string v0, "$RESUME"
            const/4 v1, 0x0
            invoke-static { p0, v0, p2, v1 }, $playInternal
            return-void
        """)
        val otherPlay = method("Ga8", listOf(string, "Z"), "V", 3, body = """
            invoke-virtual { p0, p1, p2 }, $player->A0c(${string}Z)V
            return-void
        """)
        val isPlaying = method("A0o", emptyList(), "Z", 2, flags = if (v.hiddenIsPlaying) AccessFlags.FINAL.value else PUBLIC, body = """
            iget-boolean v0, p0, $player->playing:Z
            return v0
        """)
        val setVolume = method("A0S", listOf("F", "I"), "V", 4, body = """
            const/4 v0, 0x0
            invoke-static { p1, v0 }, Ljava/lang/Math;->max(FF)F
            move-result v0
            ${if (v.clampedOnce) "" else "const/high16 p1, 0x3f800000\ninvoke-static { v0, p1 }, Ljava/lang/Math;->min(FF)F\nmove-result v0"}
            iput v0, p0, $player->volume:F
            return-void
        """)
        val methods = mutableListOf(internal, pause, stop, release, other, play, otherPlay, isPlaying, setVolume)
        if (v.secondPause) {
            methods += method("A0Y", listOf(string), "V", 3, body = """
                const-string v0, "$FRAGMENT_PAUSED"
                const-string v0, "$SINGLE_TAP_PAUSED"
                return-void
            """)
        }
        val fields = mutableListOf(
            ImmutableField(player, "A0F", groot, PUBLIC, null, null, null),
            ImmutableField(player, "playing", "Z", PUBLIC, null, null, null),
            ImmutableField(player, "volume", "F", PUBLIC, null, null, null),
        )
        if (v.secondEngineField) fields += ImmutableField(player, "A0G", groot, PUBLIC, null, null, null)
        val leave = method("onUserLeaveHint", emptyList(), "V", 1, owner = activity, body = """
            invoke-super { p0 }, Landroid/app/Activity;->onUserLeaveHint()V
            return-void
        """)
        return listOf(
            classDef(player, methods, fields),
            classDef(activity, if (v.leaveHint) listOf(leave) else emptyList(), superclass = "Landroid/app/Activity;"),
            ExtensionDex.classDef(BACKGROUND_PLAY),
        )
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.first { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    /** The references of the extension stub [name] writes, in order: types, strings, methods and fields. */
    private fun BytecodePatchContext.references(name: String): List<String> =
        method(BACKGROUND_PLAY, name).code().mapNotNull { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is StringReference -> reference.string
                null -> null
                else -> reference.toString()
            }
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.rangeRegisters(): List<Int> {
        val call = this as RegisterRangeInstruction
        return (call.startRegister until call.startRegister + call.registerCount).toList()
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    private fun method(
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        flags: Int = PUBLIC,
        owner: String = player,
        body: String,
    ): Method {
        val access = flags or (if (static) AccessFlags.STATIC.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, access, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), superclass: String = objectType): ClassDef =
        ImmutableClassDef(type, PUBLIC, superclass, null, null, null, fields, methods)

    private companion object {
        val PUBLIC = AccessFlags.PUBLIC.value
    }
}

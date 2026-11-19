/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.sleep

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.media.taptoplay.PLAY_INTERNAL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Let the screen sleep when a reel stops (#777): on every declared build, the Reels viewer's
 * keep-awake helper tells the extension its window and its boolean right after it gets the window,
 * playInternal tells it about each player it starts, the extension's playing stub asks the player,
 * and nothing else changes. A build the patch can't read fails it before an instruction changes.
 */
class ReelScreenSleepHookTest {
    @Test
    fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(reelScreenSleepPatch.default)
    }

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SCREEN_SLEEP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { it.key() }
        for (hook in listOf(KEEP_AWAKE, SLEEP_STARTED, PLAYING)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    @Test
    fun everyBuildLetsTheScreenSleep() {
        val checked = mutableListOf<String>()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) checked += letsItSleep(bundle, bundle.name)
        }
        for (base in Fixtures.otherBuilds()) checked += letsItSleep(base, base.parentFile.name)
        assertTrue("no fixture was checked", checked.isNotEmpty())
    }

    @Test
    fun anExtensionWithoutTheHooksFailsThePatch() {
        refuses("the extension has no") { PatchContexts.of(emptyList()).findReelScreenSleep() }
    }

    @Test
    fun noKeepAwakeHelperFailsThePatch() {
        refuses("expected one keep-awake helper, found 0") { PatchContexts.of(listOf(ExtensionDex.classDef(REEL_SCREEN_SLEEP))).findReelScreenSleep() }
    }

    /** A helper that does something else with getWindow's answer isn't the one the hook was written for. */
    @Test
    fun aHelperThatDoesNotKeepTheWindowFailsThePatch() {
        val context = firstOtherBuild()
        val site = context.findReelScreenSleep().keepAwake
        context.mutable(site.method).addInstructions(site.index - 1, "nop")
        refuses("doesn't keep the window") { context.findReelScreenSleep() }
    }

    /** The hook reads the helper's boolean, so a helper writing over it first would hand over the wrong one. */
    @Test
    fun aHelperThatWritesOverItsBooleanFailsThePatch() {
        val context = firstOtherBuild()
        val site = context.findReelScreenSleep().keepAwake
        context.mutable(site.method).addInstructions(0, "const/4 v${site.keep}, 0x0")
        refuses("writes over parameter 0") { context.findReelScreenSleep() }
    }

    private fun firstOtherBuild(): BytecodePatchContext {
        val build = Fixtures.otherBuilds().firstOrNull() ?: throw AssertionError("no other build to read")
        return PatchContexts.of(keptFrom(build) + ExtensionDex.classDef(REEL_SCREEN_SLEEP))
    }

    /** The build's classes calling Window.clearFlags, the player holding playInternal, and the string pools they ask. */
    private fun keptFrom(bundle: File): List<ClassDef> {
        val callers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { m -> m.code().any { it.reference() == CLEAR_FLAGS } }) callers += ImmutableClassDef.of(classDef)
            }
        }
        val players = FixtureDex.classesHolding(bundle, PLAY_INTERNAL)
        return FixtureDex.withStringPools(bundle, (callers + players).distinctBy { it.type })
    }

    private fun letsItSleep(bundle: File, label: String): String {
        val kept = keptFrom(bundle)
        val context = PatchContexts.of(kept + ExtensionDex.classDef(REEL_SCREEN_SLEEP))
        val sites = runCatching { context.findReelScreenSleep() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }

        // Read off the stock code apart from the finder: the helper is the one instance (boolean)
        // void method that both adds and clears FLAG_KEEP_SCREEN_ON on a window, and the player is
        // the class holding playInternal.
        val helpers = kept.flatMap { it.methods }.filter { method ->
            val code = method.code()
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.map(Any::toString) == listOf("Z") &&
                code.any { it.reference() == ADD_FLAGS } && code.any { it.reference() == CLEAR_FLAGS } &&
                code.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == KEEP_SCREEN_ON }
        }
        assertEquals("$label: one helper adds and clears the flag", 1, helpers.size)
        val helper = sites.keepAwake.method
        assertEquals("$label: the helper", helpers.single().key(), helper.key())
        val players = FixtureDex.classesHolding(bundle, PLAY_INTERNAL)
        assertEquals("$label: the player", players.single().type, sites.player)
        val isPlaying = context.classDefBy(sites.player).methods.single { it.key() == sites.isPlaying }
        assertTrue("$label: ${sites.isPlaying} is public", AccessFlags.PUBLIC.isSet(isPlaying.accessFlags))
        assertEquals("$label: ${sites.isPlaying} answers a boolean", "Z", isPlaying.returnType)

        val helperBefore = context.mutable(helper).code()
        val playBefore = context.mutable(sites.playInternal).code()
        context.letTheScreenSleep(sites)

        // The helper: one call right after the window's move-result, on the window and the boolean.
        val at = sites.keepAwake.index
        val helperAfter = context.mutable(helper).code()
        val where = "$label: ${helper.key()}"
        assertEquals("$where: one instruction added", helperBefore.size + 1, helperAfter.size)
        assertEquals("$where: after getWindow", GET_WINDOW, helperAfter[at - 2].reference())
        assertEquals("$where: and its move-result", Opcode.MOVE_RESULT_OBJECT, helperAfter[at - 1].opcode)
        val call = helperAfter[at] as FiveRegisterInstruction
        assertEquals("$where: the call", Opcode.INVOKE_STATIC, call.opcode)
        assertEquals("$where: the hook", KEEP_AWAKE, helperAfter[at].reference())
        val window = (helperBefore[at - 1] as OneRegisterInstruction).registerA
        val keep = helper.implementation!!.registerCount - 1
        assertEquals("$where: on the window and the boolean", listOf(2, window, keep), listOf(call.registerCount, call.registerC, call.registerD))
        assertEquals("$where: the rest is Instagram's", helperBefore.map { it.shape() }, helperAfter.filterIndexed { i, _ -> i != at }.map { it.shape() })
        assertEquals("$where: one hook", 1, helperAfter.count { it.reference() == KEEP_AWAKE })

        // playInternal: one call on the player, its first parameter.
        val playAfter = context.mutable(sites.playInternal).code()
        val started = playAfter.indices.filter { playAfter[it].reference() == SLEEP_STARTED }
        assertEquals("$label: playInternal tells the extension once", 1, started.size)
        val tell = playAfter[started.single()] as RegisterRangeInstruction
        val player = sites.playInternal.implementation!!.registerCount - 4
        assertEquals("$label: on the player", listOf(Opcode.INVOKE_STATIC_RANGE, player, 1), listOf(tell.opcode, tell.startRegister, tell.registerCount))
        assertEquals("$label: the rest of playInternal is Instagram's", playBefore.map { it.shape() }, playAfter.filterIndexed { i, _ -> i != started.single() }.map { it.shape() })

        // The stub: an instance-of the player, then its isPlaying.
        val stub = context.mutableClassDefBy(REEL_SCREEN_SLEEP).methods.single { it.key() == PLAYING }.code()
        assertEquals(
            "$label: the playing stub",
            listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN),
            stub.map { it.opcode },
        )
        assertEquals("$label: asks the player", listOf(sites.player, sites.player, sites.isPlaying), listOf(stub[0], stub[2], stub[3]).map { it.reference() })
        return label
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
        mutableClassDefBy(method.definingClass).methods.single { it.key() == method.key() }

    private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** What an instruction does, with a widened goto read as the goto it was. */
    private fun Instruction.shape(): Pair<Opcode, String?> = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    } to reference()

    private companion object {
        const val ADD_FLAGS = "Landroid/view/Window;->addFlags(I)V"
        const val PLAYING = "$REEL_SCREEN_SLEEP->playing(Ljava/lang/Object;)Z"
    }
}

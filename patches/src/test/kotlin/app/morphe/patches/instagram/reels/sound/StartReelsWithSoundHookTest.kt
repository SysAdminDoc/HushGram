/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.sound

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.reels.tapvolume.TOGGLE_AUDIO
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartReelsWithSoundHookTest {
    private val userSession = "Lcom/instagram/common/session/UserSession;"
    private val bundle = "Landroid/os/Bundle;"
    private val toggleMarker = "android_purge_26_q3_$TOGGLE_AUDIO"

    /** The onCreate hook: ask, read the source, ask with it, take the session, take the state, read it, tell, set it on. */
    private val hookLength = 21

    /** The pause hook: ask, take the state, read it, ask again, set it off. */
    private val pauseLength = 16

    /** The resume hook: ask, take the state, set it on. */
    private val resumeLength = 10

    /** The hooks the patch writes are in the ReelSound the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SOUND).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(REEL_WANTED, REEL_START_WITH_SOUND, REEL_OPENED, REEL_PAUSING, REEL_PAUSED, REEL_RESUMING,
            REEL_LETS_RESET, REEL_LETS_SOUND_ON)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * In each build of the version the Reels viewer's onCreate is found by its trace name, and the hook goes in once,
     * right after the super call: the switch first, then the viewer's config and its source, the session, the account's
     * audio state and its setter. onPause and onResume start with their own hooks, and every other instruction of the
     * three keeps its place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsReelsViewer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every build of the version", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val (viewerType, wanted) = wanted(apk)
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)
                val hooked = setOf("onCreate", "onPause", "onResume")
                val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                    .filter { it.implementation != null && it.definingClass == viewerType && it.name in hooked && it.parameterTypes.size <= 1 }
                    .associateWith(::NeutralNativePath)

                val site = context.findReelSoundSite()
                assertEquals("$what: the viewer", viewerType, site.onCreate.definingClass)
                assertEquals("$what: four borrowed locals", 4, site.scratch.distinct().size)
                assertTrue("$what: the borrowed locals fit an invoke", (site.scratch + site.pauseScratch + site.resumeScratch).all { it in 0..15 })
                assertEquals("$what: the config getter", "Lcom/instagram/clips/intf/ClipsViewerConfig;", site.config.returnType)
                assertEquals("$what: the source field", "Lcom/instagram/clips/intf/ClipsViewerSource;", site.source.type)
                val stateType = site.factory.returnType
                assertEquals("$what: the foreground listener", "onAppForegrounded", site.reset.method.name)
                assertEquals("$what: Instagram's turn-ons, the listener's and the app-open check's",
                    listOf(site.reset.method.definingClass, null),
                    site.soundOns.map { it.method.definingClass.takeIf { type -> type == site.reset.method.definingClass } })
                assertTrue("$what: the borrowed locals fit an iget and an invoke",
                    (site.soundOns + site.reset).all { it.scratch in 0..15 && it.scratch != it.state && it.scratch != it.value })
                val stateOriginals = (site.soundOns + site.reset).map { it.method }.distinctBy { "${it.definingClass}->${it.name}" }
                    .map { m ->
                        context.mutableClassDefBy(m.definingClass).methods.single {
                            it.name == m.name && it.returnType == m.returnType &&
                                it.parameterTypes.map(Any::toString) == m.parameterTypes.map(Any::toString)
                        }
                    }
                    .associateWith(::NeutralNativePath)
                context.apply { applyReelSound(site) }

                val asking = classes.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == REEL_WANTED } }
                }
                assertEquals("$what: methods asking", listOf(viewerType to "onCreate"), asking.map { it.definingClass to it.name })
                val onCreate = asking.single()
                val code = onCreate.code()
                val ask = code.indexOfFirst { it.referenceText() == REEL_WANTED }
                assertTrue("$what: right after the super call", code[ask - 1].opcode == Opcode.INVOKE_SUPER && code[ask - 1].referenceText()!!.contains("->onCreate($bundle)V"))
                // 453's setter takes a second flag, whether the user changed it, which the hook loads as 0 first.
                val extra = if (site.setter.parameterTypes.size == 2) 1 else 0
                val created = hookLength + extra
                assertEquals(
                    "$what: the hook",
                    listOf(
                        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT,
                        Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                        Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
                        Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                    ) + List(extra) { Opcode.CONST_4 } + Opcode.INVOKE_VIRTUAL,
                    code.subList(ask, ask + created).map { it.opcode },
                )
                if (extra == 1) assertEquals("$what: not the user's own change", 0, (code[ask + 20] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$what: the config", site.config.toString(), code[ask + 3].referenceText())
                assertEquals("$what: its source", site.source.toString(), code[ask + 6].referenceText())
                assertEquals("$what: asked with the source", REEL_START_WITH_SOUND, code[ask + 8].referenceText())
                assertEquals("$what: the session getter", "$viewerType->${site.session.name}()$userSession", code[ask + 11].referenceText())
                assertEquals("$what: the factory", site.factory.toString(), code[ask + 14].referenceText())
                assertEquals("$what: the getter", site.getter.toString(), code[ask + 16].referenceText())
                assertEquals("$what: told what it was", REEL_OPENED, code[ask + 18].referenceText())
                assertEquals("$what: the state to set is what it answers", Opcode.MOVE_RESULT, code[ask + 19].opcode)
                assertEquals("$what: the setter", site.setter.toString(), code[ask + 20 + extra].referenceText())
                assertTrue("$what: a jump lands inside the hook", (ask + 1 until ask + created).none { it in onCreate.jumpTargets() })
                val p0 = onCreate.implementation!!.registerCount - 2
                assertEquals("$what: the viewer is read from its own register", listOf(p0), code[ask + 3].arguments())
                assertEquals("$what: the viewer is copied from its own register", p0, (code[ask + 7] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerB)
                val borrowed = code.subList(ask, ask + created).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
                    .filter { it !in site.scratch }
                assertTrue("$what: the hook wrote a register it didn't borrow: $borrowed", borrowed.isEmpty())

                val viewer = context.mutableClassDefBy(viewerType).methods
                val onPause = viewer.single { it.name == "onPause" && it.parameterTypes.isEmpty() }
                val onResume = viewer.single { it.name == "onResume" && it.parameterTypes.isEmpty() }
                assertEquals("$what: onPause asks first", REEL_PAUSING, onPause.code()[0].referenceText())
                assertEquals("$what: onPause asks again", REEL_PAUSED, onPause.code()[11].referenceText())
                assertEquals("$what: onPause sets it off", 0, (onPause.code()[14] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$what: onPause's setter", site.setter.toString(), onPause.code()[15 + extra].referenceText())
                assertEquals("$what: onResume asks first", REEL_RESUMING, onResume.code()[0].referenceText())
                assertEquals("$what: onResume sets it on", site.setter.toString(), onResume.code()[9 + extra].referenceText())
                assertTrue("$what: a jump lands inside the pause hook", (1 until pauseLength + extra).none { it in onPause.jumpTargets() })
                assertTrue("$what: a jump lands inside the resume hook", (1 until resumeLength + extra).none { it in onResume.jumpTargets() })
                // Every call hands the setter its object and each of its flags. Two registers to the
                // (ZZ) setter verified nowhere and took the whole Reels viewer down on a device.
                for ((name, setterCall) in listOf("onCreate" to code[ask + 20 + extra], "onPause" to onPause.code()[15 + extra],
                    "onResume" to onResume.code()[9 + extra])) {
                    assertEquals("$what: $name's setter registers", site.setter.parameterTypes.size + 1, setterCall.arguments().size)
                }

                val added = mapOf(
                    "onCreate" to (ask until ask + created).toSet(),
                    "onPause" to (0 until pauseLength + extra).toSet(),
                    "onResume" to (0 until resumeLength + extra).toSet(),
                )
                for ((method, original) in originals) {
                    val mine = method.code().indices.filter { it in added.getValue(method.name) }.toSet()
                    original.assertPreserved("$what ${method.name}", method, mine)
                }

                // Instagram's clear as the app comes to the front asks first with the state's value and the
                // new one, and a no skips just the clear. Each of its two turn-ons asks with the value it sets.
                val listener = stateOriginals.keys.single { it.name == "onAppForegrounded" }
                val lcode = listener.code()
                val reset = lcode.indexOfFirst { it.referenceText() == REEL_LETS_RESET }
                assertEquals("$what: one clear hook", 1, lcode.count { it.referenceText() == REEL_LETS_RESET })
                assertEquals("$what: the clear hook", listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC),
                    lcode.subList(reset - 1, reset + 4).map { it.opcode })
                assertEquals("$what: reads the state's value", site.value.toString(), lcode[reset - 1].referenceText())
                val clear = lcode[reset + 3] as FiveRegisterInstruction
                assertEquals("$what: the clear", listOf(stateType, "Ljava/lang/Boolean;"),
                    (clear as ReferenceInstruction).reference.let { it as MethodReference }.parameterTypes.map(Any::toString))
                assertEquals("$what: the value read from the state the clear gets",
                    clear.registerC, (lcode[reset - 1] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerB)
                assertEquals("$what: asked with the clear's new value", listOf((lcode[reset - 1] as OneRegisterInstruction).registerA, clear.registerD),
                    lcode[reset].arguments())
                assertEquals("$what: a no skips just the clear", reset + 4, (lcode[reset + 2] as BuilderOffsetInstruction).target.location.index)
                assertTrue("$what: a jump lands inside the clear hook", (reset..reset + 3).none { it in listener.jumpTargets() })
                val turnOns = stateOriginals.keys.associateWith { m -> m.code().indices.filter { m.code()[it].referenceText() == REEL_LETS_SOUND_ON } }
                assertEquals("$what: one turn-on hook in each", stateOriginals.keys.map { 1 }, turnOns.values.map { it.size })
                for ((method, at) in turnOns.mapValues { it.value.single() }) {
                    val c = method.code()
                    assertEquals("$what: ${method.name}'s turn-on hook", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL),
                        c.subList(at, at + 4).map { it.opcode })
                    val on = c[at + 3] as FiveRegisterInstruction
                    assertEquals("$what: ${method.name} turns the state", stateType, ((on as ReferenceInstruction).reference as MethodReference).definingClass)
                    assertEquals("$what: ${method.name} asks with the value it sets", listOf(on.registerD), c[at].arguments())
                    assertEquals("$what: ${method.name}'s no skips just the call", at + 4, (c[at + 2] as BuilderOffsetInstruction).target.location.index)
                    assertTrue("$what: a jump lands inside ${method.name}'s hook", (at + 1..at + 3).none { it in method.jumpTargets() })
                    val mine = (at..at + 2).toSet() + if (method === listener) (reset - 1..reset + 2).toSet() else emptySet()
                    stateOriginals.getValue(method).assertPreserved("$what ${method.definingClass}->${method.name}", method, mine)
                }
            }
        }
    }

    // The viewer found by its trace name, and the types the finder walks: the viewer, the config, the toggles and what they name.
    private fun wanted(apk: File): Pair<String, Set<String>> {
        val viewers = FixtureDex.classesHolding(apk, VIEWER_TRACE).map { it.type }.distinct()
        assertEquals("${apk.name}: classes loading \"$VIEWER_TRACE\"", 1, viewers.size)
        val viewerType = viewers.single()
        val toggles = FixtureDex.classesHolding(apk, toggleMarker).map { it.type }
        val seed = setOf(viewerType, VIEWER_CONFIG) + toggles
        val loaded = FixtureDex.classes(apk, seed)
        val named = loaded.values.filter { it.type in toggles }
            .flatMap { c -> c.methods.flatMap { m -> m.code().flatMap { instruction ->
                when (val ref = instruction.reference()) {
                    is MethodReference -> listOf(ref.definingClass, ref.returnType) + ref.parameterTypes.map(Any::toString)
                    is FieldReference -> listOf(ref.definingClass, ref.type)
                    else -> emptyList()
                }
            } } }.filter { it.startsWith("L") }
        // The audio state's own field types hold its foreground listener; the app-open check loads its flag's name.
        val fields = FixtureDex.classes(apk, named.toSet()).values
            .flatMap { c -> c.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type } }
            .filter { it.startsWith("L") }
        val opening = FixtureDex.classesLoading(apk, SOUND_ON_APP_OPEN).map { it.type }
        assertTrue("${apk.name}: classes loading \"$SOUND_ON_APP_OPEN\"", opening.isNotEmpty())
        return viewerType to (seed + named + fields + opening)
    }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}

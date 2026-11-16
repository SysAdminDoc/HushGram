/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.stories.ring.COLORS_PARAMETER
import app.morphe.patches.instagram.stories.ring.ComposeRing
import app.morphe.patches.instagram.stories.ring.DEFAULT_RING
import app.morphe.patches.instagram.stories.ring.GRADIENT_PARAMETERS
import app.morphe.patches.instagram.stories.ring.GRADIENT_SPINNER
import app.morphe.patches.instagram.stories.ring.RING_COLORS
import app.morphe.patches.instagram.stories.ring.RING_DEFAULT_COLORS
import app.morphe.patches.instagram.stories.ring.RingGradient
import app.morphe.patches.instagram.stories.ring.STOPS_PARAMETER
import app.morphe.patches.instagram.stories.ring.StoryRingFixtures
import app.morphe.patches.instagram.stories.ring.colorComposeRing
import app.morphe.patches.instagram.stories.ring.colorRingGradients
import app.morphe.patches.instagram.stories.ring.findComposeRing
import app.morphe.patches.instagram.stories.ring.findRingGradients
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Story rings in the accent color on the rings the theme doesn't reach (#528): the ring view's
 * gradients and the Compose default ring go through the accent hooks, and Story ring size's own
 * guard stays first in the ring view whichever of the two patches runs first.
 */
class AccentRingsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_RINGS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(ACCENT_RING_COLORS, ACCENT_DEFAULT_RING)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    @Test
    fun aBuildWithoutTheExtensionsRingsFailsThePatch() {
        val context = PatchContexts.of(listOf(StoryRingFixtures.spinner(), StoryRingFixtures.defaultRing(), StoryRingFixtures.composeRing()))
        val refusal = assertThrows(PatchException::class.java) { context.findAccentRings() }
        assertEquals("Accent color: the extension has no public static $ACCENT_RING_COLORS", refusal.message)
    }

    /** A ring the finders can't read fails Accent color in its own name, before anything changes. */
    @Test
    fun aRingTheFindersCantReadFailsAccentColor() {
        refuses("Accent color: expected one static gradient builder", fixture(spinner = StoryRingFixtures.spinner(builders = 0)))
        refuses("Accent color: expected one place a method loading", fixture(compose = StoryRingFixtures.composeRing(calls = 0)))
        val context = PatchContexts.of(fixture(compose = StoryRingFixtures.composeRing(calls = 0)))
        assertThrows(PatchException::class.java) { context.findAccentRings() }
        val builder = context.mutableClassDefBy(GRADIENT_SPINNER).methods.single { it.name == "build" }
        assertTrue(builder.implementation!!.instructions.none { (it as? ReferenceInstruction)?.reference?.toString() == ACCENT_RING_COLORS })
    }

    /** On its own, the accent hook goes first in the builder and the setter, and right after the Compose ring's array lands. */
    @Test
    fun onItsOwnTheAccentHookGoesFirst() {
        val context = PatchContexts.of(fixture())
        val gradients = context.findRingGradients()
        val ring = context.findComposeRing()
        val originals = Originals(context, gradients, ring)
        accentColor(context)

        assertEquals(listOf("build", "keep"), gradients.map { it.name })
        originals.assertBuilders("alone", listOf(ACCENT_RING_COLORS))
        originals.assertComposeRing("alone", listOf(ACCENT_DEFAULT_RING))
    }

    /** After Story ring size, the accent hook goes behind its guard, so the color picked under Ring color is what it's given. */
    @Test
    fun afterStoryRingSizeItsGuardStaysFirst() {
        val context = PatchContexts.of(fixture())
        val originals = Originals(context, context.findRingGradients(), context.findComposeRing())
        storyRingSize(context)
        accentColor(context)

        originals.assertBuilders("after", listOf(RING_COLORS, ACCENT_RING_COLORS))
        originals.assertComposeRing("after", listOf(ACCENT_DEFAULT_RING, RING_DEFAULT_COLORS))
    }

    /**
     * Before Story ring size, its guard still ends up first in the ring view. On Compose screens the
     * later patch's hook goes first, which draws the same ring either way: the accent hook answers
     * a picked Ring color back, and Ring color answers back what it's given while none is picked.
     */
    @Test
    fun beforeStoryRingSizeItsGuardStillEndsFirst() {
        val context = PatchContexts.of(fixture())
        val originals = Originals(context, context.findRingGradients(), context.findComposeRing())
        accentColor(context)
        storyRingSize(context)

        originals.assertBuilders("before", listOf(RING_COLORS, ACCENT_RING_COLORS))
        originals.assertComposeRing("before", listOf(RING_DEFAULT_COLORS, ACCENT_DEFAULT_RING))
    }

    /** In each declared build both patches take every ring in either order. */
    @Test
    fun eachDeclaredBuildTakesBothInEitherOrder() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                takesBoth(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildTakesBothInEitherOrderToo() {
        for (base in Fixtures.otherBuilds()) takesBoth(base, base.parentFile.name)
    }

    private fun takesBoth(bundle: File, label: String) {
        val classes = (FixtureDex.classesLoading(bundle, DEFAULT_RING) + FixtureDex.classes(bundle, setOf(GRADIENT_SPINNER)).values +
            ExtensionDex.classDef(ACCENT_RINGS)).distinctBy { it.type }

        val after = PatchContexts.of(classes)
        val afterOriginals = Originals(after, after.findRingGradients(), after.findComposeRing())
        storyRingSize(after)
        accentColor(after)
        afterOriginals.assertBuilders("$label after", listOf(RING_COLORS, ACCENT_RING_COLORS))
        afterOriginals.assertComposeRing("$label after", listOf(ACCENT_DEFAULT_RING, RING_DEFAULT_COLORS))

        val before = PatchContexts.of(classes)
        val beforeOriginals = Originals(before, before.findRingGradients(), before.findComposeRing())
        accentColor(before)
        storyRingSize(before)
        beforeOriginals.assertBuilders("$label before", listOf(RING_COLORS, ACCENT_RING_COLORS))
        beforeOriginals.assertComposeRing("$label before", listOf(RING_DEFAULT_COLORS, ACCENT_DEFAULT_RING))
    }

    private fun refuses(why: String, classes: List<ClassDef>) {
        val refusal = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findAccentRings() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().startsWith(why))
    }

    /** What Story ring size does to the same rings, in its order: everything found, then the hooks. */
    private fun storyRingSize(context: BytecodePatchContext) {
        val gradients = context.findRingGradients()
        val ring = context.findComposeRing()
        context.colorRingGradients(gradients)
        context.colorComposeRing(ring, RING_DEFAULT_COLORS)
    }

    private fun accentColor(context: BytecodePatchContext) {
        context.colorAccentRings(context.findAccentRings())
    }

    private fun fixture(
        spinner: ClassDef = StoryRingFixtures.spinner(setters = 1),
        compose: ClassDef = StoryRingFixtures.composeRing(),
    ): List<ClassDef> = listOf(spinner, StoryRingFixtures.defaultRing(), compose, ExtensionDex.classDef(ACCENT_RINGS))

    /** Each ring the patches change, as it was before either did, to check what they left. */
    private class Originals(val context: BytecodePatchContext, val gradients: List<RingGradient>, val ring: ComposeRing) {
        private val builders = gradients.map { NeutralNativePath(builder(it)) }
        private val compose = NeutralNativePath(composeMethod())

        /** [hooks] in order at the top of each builder, each passed the colors and the stops and answering into the colors. */
        fun assertBuilders(what: String, hooks: List<String>) {
            for ((gradient, original) in gradients.zip(builders)) {
                val method = builder(gradient)
                val code = method.implementation!!.instructions.toList()
                val first = method.implementation!!.registerCount - GRADIENT_PARAMETERS.size
                val called = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() in ALL_HOOKS }
                assertEquals("$what ${gradient.name}: hooks", hooks.indices.map { it * 2 }, called)
                for ((step, hook) in hooks.withIndex()) {
                    val call = code[step * 2] as FiveRegisterInstruction
                    assertEquals("$what ${gradient.name}: hook $step", hook, (call as ReferenceInstruction).reference.toString())
                    assertEquals("$what ${gradient.name}: hook $step's call", Opcode.INVOKE_STATIC, code[step * 2].opcode)
                    assertEquals("$what ${gradient.name}: the colors and the stops",
                        listOf(first + COLORS_PARAMETER, first + STOPS_PARAMETER), listOf(call.registerC, call.registerD).take(call.registerCount))
                    assertEquals("$what ${gradient.name}: the answer", Opcode.MOVE_RESULT_OBJECT, code[step * 2 + 1].opcode)
                    assertEquals("$what ${gradient.name}: into the colors", first + COLORS_PARAMETER, (code[step * 2 + 1] as OneRegisterInstruction).registerA)
                }
                assertTrue("$what ${gradient.name}: something jumps onto a hook", method.jumpTargets().none { it < hooks.size * 2 })
                original.assertPreserved("$what ${gradient.name}", method, (0 until hooks.size * 2).toSet())
            }
        }

        /** [hooks] in order right after the Compose ring's array lands, each passed it alone and answering into its register. */
        fun assertComposeRing(what: String, hooks: List<String>) {
            val method = composeMethod()
            val code = method.implementation!!.instructions.toList()
            val called = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() in ALL_HOOKS }
            assertEquals("$what Compose ring: hooks", hooks.indices.map { ring.at + it * 2 }, called)
            for ((step, hook) in hooks.withIndex()) {
                val at = ring.at + step * 2
                assertEquals("$what Compose ring: hook $step", hook, (code[at] as ReferenceInstruction).reference.toString())
                assertEquals("$what Compose ring: hook $step's call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
                val call = code[at] as RegisterRangeInstruction
                assertEquals("$what Compose ring: the colors", ring.register to 1, call.startRegister to call.registerCount)
                assertEquals("$what Compose ring: the answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                assertEquals("$what Compose ring: into the colors", ring.register, (code[at + 1] as OneRegisterInstruction).registerA)
            }
            val added = (ring.at until ring.at + hooks.size * 2).toSet()
            assertTrue("$what Compose ring: something jumps onto a hook", method.jumpTargets().none { it in added })
            compose.assertPreserved("$what Compose ring", method, added)
        }

        private fun builder(gradient: RingGradient): Method = context.mutableClassDefBy(GRADIENT_SPINNER).methods.single {
            it.name == gradient.name && it.parameterTypes.map(CharSequence::toString) == gradient.parameters
        }

        private fun composeMethod(): Method = context.mutableClassDefBy(ring.type).methods.single {
            it.name == ring.name && it.parameterTypes.map(CharSequence::toString) == ring.parameters
        }
    }

    private companion object {
        val ALL_HOOKS = setOf(RING_COLORS, ACCENT_RING_COLORS, RING_DEFAULT_COLORS, ACCENT_DEFAULT_RING)
    }
}

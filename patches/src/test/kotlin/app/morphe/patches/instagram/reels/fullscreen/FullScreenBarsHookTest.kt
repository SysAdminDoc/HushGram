/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.glass.glassTabBarPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #147: Instagram's window insets listener asks the extension about each bar height it keeps, the
 * status bar's through barHeight and the navigation bar's through navBarHeight (#144).
 */
class FullScreenBarsHookTest {
    private companion object {
        const val ACTIVITY_TYPE = "Landroid/app/Activity;"
    }

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FULL_SCREEN_BARS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(BAR_HEIGHT_HOOK, NAV_BAR_HEIGHT_HOOK, KEEP_STATUS_BAR_HEIGHT, KEEP_NAV_BAR_HEIGHT, HEIGHTS_ASKED, HEIGHTS_DROPPED, TELL_HEIGHTS)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** Full screen and Glass tab bar share the one routing, so the listener asks once per height whichever is in (#144). */
    @Test
    fun bothPatchesShareTheRouting() {
        assertTrue(barHeightsPatch in fullScreenBarsPatch.dependencies)
        assertTrue(barHeightsPatch in glassTabBarPatch.dependencies)
    }

    /** In each declared build both stores ask first, whether the classes are copied or read as the patcher reads an APK. */
    @Test
    fun eachDeclaredBuildAsksAboutBothBarHeights() {
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

    @Test
    fun eachOtherBuildAsksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /**
     * #196: the extension's two stubs store a height where the listener keeps each bar's, so a height
     * answered 0 can be put back as an activity pauses. Each stub writes its parameter to the field the
     * listener stores that bar's height in, and the fields are public statics it can reach.
     */
    @Test
    fun eachDeclaredBuildCanPutTheHeightsBack() {
        forEachDeclaredBundle { bundle, types ->
            val stores = PatchContexts.of(FixtureDex.classes(bundle, types).values).findBarHeights().barHeightStores()
            val holders = stores.map { it.field.definingClass }.toSet()
            val context = PatchContexts.of(FixtureDex.classes(bundle, types + holders).values + ExtensionDex.classDef(FULL_SCREEN_BARS))

            context.fillKeptHeights(context.findBarHeights().barHeightStores())

            val bars = context.mutableClassDefBy(FULL_SCREEN_BARS)
            for ((store, stub) in stores.zip(listOf(KEEP_STATUS_BAR_HEIGHT, KEEP_NAV_BAR_HEIGHT))) {
                val what = "${bundle.name}: $stub"
                val method = bars.methods.single { it.descriptor() == stub }
                val code = method.code()
                val parameter = method.implementation!!.registerCount - 1
                assertEquals("$what: stores first", Opcode.SPUT, code[0].opcode)
                assertEquals("$what: its parameter", parameter, (code[0] as OneRegisterInstruction).registerA)
                assertEquals("$what: to the field the listener stores", store.field.toString(), code[0].referenceText())
                assertEquals("$what: then returns", Opcode.RETURN_VOID, code[1].opcode)
            }
        }
    }

    /**
     * #200: registering a screen's bar heights listener and removing one tell FullScreenBars first, on
     * their own activity and listener, and its stub calls the listener's own method with the two heights.
     */
    @Test
    fun eachDeclaredBuildFollowsTheHeightListeners() {
        forEachDeclaredBundle { bundle, types ->
            val found = PatchContexts.of(FixtureDex.classes(bundle, types).values).findBarHeights()
            val holder = found.barHeightStores().map { it.field.definingClass }.distinct().single()
            val call = found.heightsListenerCall()
            val context = PatchContexts.of(
                FixtureDex.classes(bundle, types + holder + call.definingClass).values + ExtensionDex.classDef(FULL_SCREEN_BARS),
            )

            context.followHeightListeners(holder, call)

            val what = bundle.name
            val held = context.mutableClassDefBy(holder)
            for ((parameters, hook) in listOf(
                listOf(ACTIVITY_TYPE, call.definingClass, "Z") to HEIGHTS_ASKED,
                listOf(ACTIVITY_TYPE, call.definingClass) to HEIGHTS_DROPPED,
            )) {
                val method = held.methods.single {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == parameters
                }
                val code = method.code()
                val first = method.implementation!!.registerCount - parameters.size
                assertEquals("$what: ${method.name} asks first", hook, code[0].referenceText())
                assertEquals("$what: ${method.name}'s activity and listener", listOf(first, first + 1), code[0].arguments())
            }
            val tell = context.mutableClassDefBy(FULL_SCREEN_BARS).methods.single { it.descriptor() == TELL_HEIGHTS }
            val code = tell.code()
            val listener = tell.implementation!!.registerCount - 3
            assertEquals("$what: the listener is cast", Opcode.CHECK_CAST, code[0].opcode)
            assertEquals("$what: to the listener type", call.definingClass, code[0].referenceText())
            assertEquals("$what: its own method", Opcode.INVOKE_INTERFACE, code[1].opcode)
            assertEquals("$what: the one the insets listener calls", call.toString(), code[1].referenceText())
            assertEquals("$what: with the two heights", listOf(listener, listener + 1, listener + 2), code[1].arguments())
            assertEquals("$what: then returns", Opcode.RETURN_VOID, code[2].opcode)
        }
    }

    /** Runs [check] on every fixture of each declared build, with the types holding the insets listener. */
    private fun forEachDeclaredBundle(check: (File, Set<String>) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle, FixtureDex.classesHolding(bundle, STATUS_BAR_CHANGED).map { it.type }.toSet())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Method.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun builds(bundle: File, label: String) {
        val types = FixtureDex.classesHolding(bundle, STATUS_BAR_CHANGED).map { it.type }.toSet()
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "$label ($read)"
            val classes = classesOf(bundle, types).values
            assertEquals("$what: the listener's class", types, classes.map { it.type }.toSet())
            val context = PatchContexts.of(classes)
            val listener = context.findBarHeights()
            val before = listener.code()
            val native = NeutralNativePath(listener)
            val stores = listener.barHeightStores()

            routeBarHeights(listener)

            val code = listener.code()
            assertEquals("$what: two instructions added per store", before.size + 4, code.size)
            val hooks = setOf(BAR_HEIGHT_HOOK, NAV_BAR_HEIGHT_HOOK)
            val asks = code.indices.filter { code[it].referenceText() in hooks }
            assertEquals("$what: asks once per height", 2, asks.size)
            for ((ask, store) in asks.zip(stores.sortedBy { it.index })) {
                val hook = if (store == stores[0]) BAR_HEIGHT_HOOK else NAV_BAR_HEIGHT_HOOK
                assertEquals("$what: the status bar asks barHeight, the navigation bar navBarHeight", hook, code[ask].referenceText())
                assertEquals("$what: the ask is an invoke-static", Opcode.INVOKE_STATIC, code[ask].opcode)
                assertEquals("$what: on the height", listOf(store.register), code[ask].arguments())
                assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[ask + 1].opcode)
                assertEquals("$what: in the height's register", store.register, (code[ask + 1] as OneRegisterInstruction).registerA)
                assertEquals("$what: stored", Opcode.SPUT, code[ask + 2].opcode)
                assertEquals("$what: from the answer", store.register, (code[ask + 2] as OneRegisterInstruction).registerA)
                assertEquals("$what: to the height's field", store.field.toString(), (code[ask + 2] as ReferenceInstruction).reference.toString())
            }
            val heights = stores.map { it.field.toString() }.toSet()
            assertEquals("$what: the status bar's and the navigation bar's fields", 2, heights.size)
            val unasked = code.indices.filter { at ->
                code[at].opcode == Opcode.SPUT && (code[at].reference() as FieldReference).toString() in heights &&
                    code.getOrNull(at - 2)?.referenceText() !in hooks
            }
            assertEquals("$what: a height stored without asking", emptyList<Int>(), unasked)
            val added = asks.flatMap { listOf(it + 1, it + 2) }.toSet()
            native.assertPreserved(what, listener, added, asks.toSet())
        }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> {
        val five = this as FiveRegisterInstruction
        return listOf(five.registerC, five.registerD, five.registerE, five.registerF, five.registerG).take(five.registerCount)
    }
}

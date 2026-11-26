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
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.glass.glassTabBarPatch
import app.morphe.patches.instagram.misc.theme.THREAD_BACKGROUND_IDS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        const val CHAT = "Lfixture/ThemedChat;"
        val INSET = ImmutableMethodReference(
            "Landroid/graphics/drawable/LayerDrawable;", "setLayerInset", listOf("I", "I", "I", "I", "I"), "V",
        )
        val HAND_OFF = ImmutableMethodReference(
            "Lfixture/Apply;", "set", listOf("Landroid/graphics/drawable/Drawable;", "Landroid/view/View;", "I"), "V",
        )
    }

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FULL_SCREEN_BARS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(BAR_HEIGHT_HOOK, NAV_BAR_HEIGHT_HOOK, KEEP_STATUS_BAR_HEIGHT, KEEP_NAV_BAR_HEIGHT, HEIGHTS_ASKED, HEIGHTS_DROPPED, TELL_HEIGHTS, TAB_BAR_KEPT_HOOK, CHAT_THEME_STRIP)) {
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
            val kept = FixtureDex.classes(bundle, setOf(holder)).getValue(holder).fields
                .filter { AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }.toSet()
            val context = PatchContexts.of(
                FixtureDex.classes(bundle, types + holder + call.definingClass + kept).values + ExtensionDex.classDef(FULL_SCREEN_BARS),
            )
            val homes = context.heightListenerHomes(holder)
            assertTrue("${bundle.name}: the holder is a home of its listeners", holder in homes)

            context.followHeightListeners(holder, call)

            val what = bundle.name
            for ((parameters, hook) in listOf(
                listOf(ACTIVITY_TYPE, call.definingClass, "Z") to HEIGHTS_ASKED,
                listOf(ACTIVITY_TYPE, call.definingClass) to HEIGHTS_DROPPED,
            )) {
                val method = homes.flatMap { context.mutableClassDefBy(it).methods }.single {
                    it.returnType == "V" && it.parameterTypes.map(Any::toString) == parameters
                }
                val code = method.code()
                // The activity and the listener are the first two parameters, past this on an instance method.
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

    /**
     * A chat's tab bar stays hidden through a tell: the main activity's method that shows or hides
     * the bar, the one private (IZ)V its layout for a new screen size calls on it, asks
     * [TAB_BAR_KEPT_HOOK] first with the visibility and returns when told to, and is otherwise as it
     * was. It's the method that hands that visibility to the bar's view.
     */
    @Test
    fun eachBuildKeepsTheTabBarThroughATell() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        assertEquals("a declared build has no fixture", versions, versions.filter { v -> bundles.any { it.name.contains("-$v-") } }.toSet())
        for (bundle in bundles + Fixtures.otherBuilds()) {
            val label = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val types = FixtureDex.classesLoading(bundle, TAB_BAR_MIGRATION).map { it.type }.toSet()
            assertTrue("$label: the main activity loads $TAB_BAR_MIGRATION", MAIN_ACTIVITY in types)
            val context = PatchContexts.of(FixtureDex.classes(bundle, types).values)
            val method = context.tabBarVisibilityMethod()
            val what = "$label: ${method.name}"
            val visibility = method.implementation!!.registerCount - method.parameterTypes.size
            assertEquals("$what: the visibility first", TAB_BAR_VISIBILITY_PARAMETERS, method.parameterTypes.map(Any::toString))
            assertTrue("$what hands the visibility to a view", method.code().any {
                it.opcode == Opcode.INVOKE_STATIC && (it.reference() as? MethodReference)?.parameterTypes?.map(Any::toString) ==
                    listOf("Landroid/view/View;", "I", "I") && it.arguments().getOrNull(1) == visibility
            })
            val native = NeutralNativePath(method)

            context.keepTabBarThroughTells()

            val code = method.code()
            assertEquals("$what asks first", TAB_BAR_KEPT_HOOK, code[0].referenceText())
            assertEquals("$what: an invoke-static", Opcode.INVOKE_STATIC, code[0].opcode)
            assertEquals("$what: with the visibility", listOf(visibility), code[0].arguments())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
            val answer = (code[1] as OneRegisterInstruction).registerA
            assertTrue("$what: in a register of its own", answer < visibility - 1)
            assertEquals("$what: a no goes on", Opcode.IF_EQZ, code[2].opcode)
            assertEquals("$what: tested on the answer", answer, (code[2] as OneRegisterInstruction).registerA)
            assertEquals("$what: to the stock code", code[2].codeUnits + code[3].codeUnits, (code[2] as OffsetInstruction).codeOffset)
            assertEquals("$what: a yes returns", Opcode.RETURN_VOID, code[3].opcode)
            native.assertPreserved(what, method, setOf(0, 1, 2, 3))
        }
    }

    /**
     * #187: in every build the method painting an opened chat's background asks chatThemeStrip for the
     * status bar's height, read from the window's insets, right before the sub-int making its theme
     * strip's bottom inset, and keeps Instagram's code, branches and handlers around the ask.
     */
    @Test
    fun everyBuildAsksForAThemedChatsStrip() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                asksForTheStrip(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) asksForTheStrip(base, base.parentFile.name)
    }

    private fun asksForTheStrip(bundle: File, label: String) {
        val classes = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.code().any { it.loadsThreadBackground() } }) classes += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(classes + ImmutableClassDef.of(ExtensionDex.classDef(FULL_SCREEN_BARS)))
        val site = context.findChatThemeStrip()
        assertNotNull("$label: the themed chat's strip", site)
        val stock = site!!.method
        val before = stock.code()
        assertEquals("$label: the strip's inset is made by a sub-int", Opcode.SUB_INT, before[site.at].opcode)
        val sub = before[site.at] as ThreeRegisterInstruction
        assertEquals("$label: from the bar's height", site.height, sub.registerC)
        val inset = (site.at + 1 until before.size).first { before[it].referenceText()?.endsWith("->setLayerInset(IIIII)V") == true }
        val call = before[inset] as RegisterRangeInstruction
        assertEquals("$label: the sub-int makes the first inset's bottom", sub.registerA, call.startRegister + call.registerCount - 1)
        val read = (0 until site.at).lastOrNull { before[it].opcode == Opcode.IGET && (before[it] as OneRegisterInstruction).registerA == site.height }
        assertNotNull("$label: the bar's height read from the insets", read)
        assertEquals("$label: an int", "I", (before[read!!].reference() as FieldReference).type)
        val native = NeutralNativePath(stock)

        context.closeChatThemeStrip()

        val method = context.mutableClassDefBy(stock.definingClass).methods.single {
            it.name == stock.name && it.parameterTypes.map(Any::toString) == stock.parameterTypes.map(Any::toString)
        }
        val code = method.code()
        assertEquals("$label: asks once, before the sub-int", listOf(site.at), code.indices.filter { code[it].referenceText() == CHAT_THEME_STRIP })
        assertEquals("$label: on the bar's height", listOf(site.height, 1), (code[site.at] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[site.at + 1].opcode)
        assertEquals("$label: in the height's register", site.height, (code[site.at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: then the sub-int", Opcode.SUB_INT, code[site.at + 2].opcode)
        native.assertPreserved(label, method, setOf(site.at, site.at + 1))
    }

    private fun Instruction.loadsThreadBackground(): Boolean =
        this is NarrowLiteralInstruction && (narrowLiteral.toLong() and 0xffffffffL) in THREAD_BACKGROUND_IDS

    /**
     * #187: a branch onto the sub-int making the strip's inset lands on the ask, so both ways to the strip
     * ask. A chat background whose inset isn't a sub-int has no strip found, no chat background leaves
     * everything as it is, and without the hook in the extension it refuses under the patch's name.
     */
    @Test
    fun bothWaysToTheStripAsk() {
        val context = PatchContexts.of(listOf(themedChat(), ImmutableClassDef.of(ExtensionDex.classDef(FULL_SCREEN_BARS))))
        val site = context.findChatThemeStrip()
        assertNotNull(site)
        assertEquals(listOf("paint", 5, 2), listOf(site!!.method.name, site.at, site.height))

        context.closeChatThemeStrip()

        val methods = context.mutableClassDefBy(CHAT).methods.associateBy { it.name }
        val code = methods.getValue("paint").code()
        assertEquals("the ask", listOf(5), code.indices.filter { code[it].referenceText() == CHAT_THEME_STRIP })
        assertEquals("the branch lands on the ask", 5, target(code, 3))
        assertEquals("the answer", 2, (code[6] as OneRegisterInstruction).registerA)
        assertEquals("then the inset", Opcode.SUB_INT, code[7].opcode)
        assertTrue("added: no ask", methods.getValue("added").code().none { it.referenceText() == CHAT_THEME_STRIP })

        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(themedChat())).findChatThemeStrip() }
        assertTrue(failure.message, failure.message!!.startsWith(FULL_SCREEN_BARS_PATCH))
        val none = PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(FULL_SCREEN_BARS))))
        assertNull(none.findChatThemeStrip())
        none.closeChatThemeStrip()
    }

    /**
     * A chat class whose paint loads the thread background id, builds layers into v1, takes a height into
     * v2 one of two ways and insets the layers by v3 less it:
     * `const v0, id; new-instance v1; const/4 v2, 5; if-eqz v0, inset; const/4 v2, 3; inset: sub-int v6, v3, v2;
     * invoke-virtual/range {v1 .. v6} setLayerInset; const/4 v0, 0; invoke-static {v1, p0, v0}; return-void`.
     * added makes the inset with an add-int instead.
     */
    private fun themedChat(): ClassDef {
        val id = THREAD_BACKGROUND_IDS.first().toInt()
        fun paint(make: Opcode) = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference("Landroid/graphics/drawable/LayerDrawable;")),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 5),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 3),
            ImmutableInstruction11n(Opcode.CONST_4, 2, 3),
            ImmutableInstruction23x(make, 6, 3, 2),
            ImmutableInstruction3rc(Opcode.INVOKE_VIRTUAL_RANGE, 1, 6, INSET),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 7, 0, 0, 0, HAND_OFF),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val methods = listOf("paint" to Opcode.SUB_INT, "added" to Opcode.ADD_INT).map { (name, make) ->
            ImmutableMethod(
                CHAT, name, listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(8, paint(make), null, null),
            )
        }
        return ImmutableClassDef(CHAT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    }

    /** The index of the instruction the branch at [at] goes to. */
    private fun target(code: List<Instruction>, at: Int): Int {
        val offsets = code.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
        return offsets.indexOf(offsets[at] + (code[at] as OffsetInstruction).codeOffset)
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

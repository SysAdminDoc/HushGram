/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StopReelsScrollingHookTest {
    private val viewerType = "Lfixture/ReelsViewer;"
    private val pagerImpl = "Lfixture/ReelsPager;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val view = "Landroid/view/View;"
    private val bundle = "Landroid/os/Bundle;"
    private val event = "Landroid/view/MotionEvent;"
    private val pagerField = "$pagerImpl->pager:$VIEW_PAGER"
    private val inputField = "$VIEW_PAGER->userInput:Z"
    private val setUserInput = "$VIEW_PAGER->$SET_USER_INPUT(Z)V"

    /** The three hooks the patch writes are in the ReelScrolling the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_SCROLLING).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(PAGER_HOOK, USER_INPUT_HOOK, PULL_HOOK)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The viewer asks about its pager right after storing it and, on a 0, turns its input off; the
     * pager's setter asks about every value first; each touch handler of the pull-down layout asks
     * first and returns false on a 0. Each hook goes in once.
     */
    @Test
    fun eachPlaceAsksOnceAndGoesOnAsBefore() {
        val context = PatchContexts.of(classes())

        context.stop()

        assertPagerHeld("the stand-in", context.viewer(viewerType), pager = 2, answer = 0, after = Opcode.GOTO)
        assertSetterAsks("the stand-in", context.setter(), self = 1)
        for (name in PULL_TOUCHES) assertPullAsks("the stand-in", context.touch(name), free = 0)
        val calls = listOf(viewerType, VIEW_PAGER, PULL_LAYOUT, pagerImpl).sumOf { type ->
            context.mutableClassDefBy(type).methods.sumOf { method ->
                method.code().count { it.referenceText() in listOf(PAGER_HOOK, USER_INPUT_HOOK, PULL_HOOK) }
            }
        }
        assertEquals("one call for each place", 4, calls)
        assertPagerBridge("the stand-in", context, "$pagerImpl->config0:$VIEWER_CONFIG")
    }

    /**
     * An object holding the pager whose class or config field isn't public keeps the bridge's stub,
     * which answers no config, so the viewer goes by its source (#804). The hook still gets the object.
     */
    @Test
    fun aPagerHolderTheExtensionCantReadKeepsTheStub() {
        for (bridge in listOf("private class", "private config")) {
            val context = PatchContexts.of(classes(bridge = bridge))

            context.stop()

            assertPagerHeld(bridge, context.viewer(viewerType), pager = 2, answer = 0, after = Opcode.GOTO)
            assertPagerBridge(bridge, context, null)
        }
    }

    /**
     * Code another patch puts into onViewCreated ahead of the store, between the find and the apply,
     * as #390's pager hook does in 453, still leaves the question right after the store.
     */
    @Test
    fun codePutAheadOfTheStoreSinceDoesntMoveTheHook() {
        val context = PatchContexts.of(classes())
        val found = context.findReelsScrolling()
        context.viewer(viewerType).addInstructionsWithLabels(0, "const/4 v0, 0x0\nconst/4 v0, 0x1")

        context.stopReelsScrolling(found)

        assertPagerHeld("the stand-in", context.viewer(viewerType), pager = 2, answer = 0, after = Opcode.GOTO)
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val stored = "expected $viewerType->onViewCreated to store $pagerField once, found"
        val switch = "expected one static method of $pagerImpl taking it that reads $pagerField and calls $VIEW_PAGER->$SET_USER_INPUT, found"
        val setup = "expected one method handed a $view to store $pagerField, found"
        val cases = listOf(
            classes(lookups = 0) to "expected one method loading \"$PAGER_VIEW_LOOKUP\", found 0",
            classes(lookups = 2) to "expected one method loading \"$PAGER_VIEW_LOOKUP\", found 2",
            classes(pagerFields = 2) to "expected $pagerImpl to hold one $VIEW_PAGER field, found 2",
            classes(switches = 0) to "$switch 0",
            classes(switches = 2) to "$switch 2",
            classes(switchReadsPager = false) to "$switch 0",
            classes(switchSetsInput = false) to "$switch 0",
            classes(viewers = 0) to "$setup 0",
            classes(viewers = 2) to "$setup 2",
            classes(stores = 0) to "$setup 0",
            classes(stores = 2) to "$stored 2",
            classes(busyLocals = true) to "$viewerType->onViewCreated has no local register up to v15 that nothing reads after storing its pager",
            classes(pagerConfigs = 0) to "$pagerImpl holds 0 $VIEWER_CONFIG fields, not one",
            classes(configClass = false) to "$VIEWER_CONFIG isn't in this build",
            classes(configSources = 2) to "$VIEWER_CONFIG holds 2 $VIEWER_SOURCE fields, not one",
            classes(pagerClass = false) to "$VIEW_PAGER isn't in this build",
            classes(setterName = "setInputEnabled") to "expected one $SET_USER_INPUT(Z)V in $VIEW_PAGER, found 0",
            classes(setterStatic = true) to "$VIEW_PAGER->$SET_USER_INPUT isn't an instance method with code",
            classes(setterStores = 0) to "expected $VIEW_PAGER->$SET_USER_INPUT to store its parameter in one field, found 0",
            classes(setterStores = 2) to "expected $VIEW_PAGER->$SET_USER_INPUT to store its parameter in one field, found 2",
            classes(setterLoops = true) to "something in $VIEW_PAGER->$SET_USER_INPUT jumps back to its start",
            classes(setterOverwrites = true) to "writes over parameter 0",
            classes(layoutClass = false) to "$PULL_LAYOUT isn't in this build",
            classes(touches = listOf("onTouchEvent")) to "expected one onInterceptTouchEvent($event)Z in $PULL_LAYOUT, found 0",
            classes(touches = listOf("onInterceptTouchEvent")) to "expected one onTouchEvent($event)Z in $PULL_LAYOUT, found 0",
            classes(touchStatic = true) to "$PULL_LAYOUT->onInterceptTouchEvent isn't an instance method with code",
            classes(touchLocals = 0) to "$PULL_LAYOUT->onInterceptTouchEvent has 0 local register(s) up to v15 that nothing reads after instruction 0, needs 1",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.stop() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    assertEquals(
                        "$expected: ${original.type}->${method.name} changed",
                        method.code().map { it.describe() }, now.getValue(method.key()).code().map { it.describe() },
                    )
                }
            }
        }
    }

    /**
     * In each declared build the Reels viewer asks once after storing its pager, AndroidX's setter
     * asks first, and both touch handlers of the pull-down layout ask first, whether the classes are
     * copied or read as the patcher reads an APK.
     */
    @Test
    fun eachDeclaredBuildHoldsItsReelsPager() {
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
    fun eachOtherBuildHoldsItsReelsPagerToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /**
     * Every source the extension sorts viewers by is a constant of each declared build's ClipsViewerSource,
     * so none of the three switches names one that isn't there.
     */
    @Test
    fun theSourcesItSortsByAreInstagrams() {
        val named = ExtensionDex.classDef(REEL_SCROLLING).methods.single { it.name == "<clinit>" }.code()
            .mapNotNull { (it.reference() as? StringReference)?.string }
            .filter { it.matches(Regex("[A-Z][A-Z0-9_]+")) }.toSet()
        assertTrue("the extension's source names: $named", "PROFILE" in named && "SONG" in named && "HASHTAG" in named)
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val enum = FixtureDex.classes(bundle, setOf(VIEWER_SOURCE)).getValue(VIEWER_SOURCE)
                val constants = enum.methods.single { it.name == "<clinit>" }.code()
                    .mapNotNull { (it.reference() as? StringReference)?.string }.toSet()
                assertEquals("${bundle.name}: names ClipsViewerSource doesn't have", emptySet<String>(), named - constants)
                checked++
            }
        }
        assertTrue("no declared build had a fixture", checked > 0)
    }

    private fun builds(bundle: File, label: String) {
        val holders = reelsPagerHolders(bundle)
        val types = holders + VIEW_PAGER + PULL_LAYOUT + VIEWER_CONFIG
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "${label} ($read)"
            val classes = classesOf(bundle, types).values
            assertEquals("$what: the viewer, the pager, AndroidX's pager and the layout", types, classes.map { it.type }.toSet())
            val context = PatchContexts.of(classes + ImmutableClassDef.of(ExtensionDex.classDef(REEL_SCROLLING)))
            val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                .filter { it.implementation != null }.associateWith(::NeutralNativePath)

            val site = context.findReelsScrolling()
            context.stopReelsScrolling(site)
            assertTrue("$what: the object the pager goes into and its config are public", site.bridged)
            assertPagerBridge(what, context, site.config)

            val asking = holders.flatMap { type ->
                context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.referenceText() == PAGER_HOOK } }
            }
            assertEquals("$what: methods asking about the pager", 1, asking.size)
            val viewer = asking.single()
            val code = viewer.code()
            val ask = code.indexOfFirst { it.referenceText() == PAGER_HOOK }
            val store = code[ask - 4]
            assertEquals("$what: the store", Opcode.IPUT_OBJECT, store.opcode)
            assertEquals("$what: the pager's type", VIEW_PAGER, (store.reference() as FieldReference).type)
            val pager = (store as TwoRegisterInstruction).registerA
            val answer = (code[ask + 1] as OneRegisterInstruction).registerA
            assertPagerHeld(what, viewer, pager, answer, after = code[ask + 5].opcode)
            assertSetterAsks(what, context.setter(), self = context.setter().implementation!!.registerCount - 2)
            for (name in PULL_TOUCHES) {
                val touch = context.touch(name)
                assertPullAsks(what, touch, free = (touch.code()[1] as OneRegisterInstruction).registerA)
            }
            for ((method, original) in originals) {
                val added = method.code().indices.flatMap { at ->
                    when (method.code()[at].referenceText()) {
                        PAGER_HOOK -> (at - 3 until at + 5).toList()
                        PULL_HOOK -> (at until at + 5).toList()
                        USER_INPUT_HOOK -> (at until at + 2).toList()
                        else -> emptyList()
                    }
                }.toSet()
                original.assertPreserved("$what ${method.name}", method, added)
            }
        }
    }

    /**
     * The bridge the extension reads a viewer's config through (#804), off the object its pager went
     * into: that object cast, then its [config] field. With [config] null, the stub stays as shipped.
     */
    private fun assertPagerBridge(what: String, context: BytecodePatchContext, config: String?) {
        val code = context.mutableClassDefBy(REEL_SCROLLING).methods.single { it.name == PAGER_CONFIG }.code()
        if (config == null) {
            val stub = ExtensionDex.classDef(REEL_SCROLLING).methods.single { it.name == PAGER_CONFIG }.code()
            assertEquals("$what: the stub stays", stub.map { it.opcode }, code.map { it.opcode })
            return
        }
        assertEquals("$what: the bridge", listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), code.map { it.opcode })
        assertEquals("$what: the object, then its config", listOf(config.substringBefore("->"), config, null), code.map { it.referenceText() })
        assertEquals("$what: in the parameter's register", listOf(0, 0), (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
    }

    /**
     * After the store: the viewer's source read from the object the pager went into, the hook on the
     * pager, the source and that object, its answer, a skip on 1, and the pager's input turned off with a 0.
     */
    private fun assertPagerHeld(what: String, viewer: MutableMethod, pager: Int, answer: Int, after: Opcode) {
        val code = viewer.code()
        val asks = code.indices.filter { code[it].referenceText() == PAGER_HOOK }
        assertEquals("$what: asks about the pager", 1, asks.size)
        val ask = asks.single()
        val store = code[ask - 4] as TwoRegisterInstruction
        assertEquals("$what: right after the store", Opcode.IPUT_OBJECT, code[ask - 4].opcode)
        val config = code[ask - 3] as TwoRegisterInstruction
        assertEquals("$what: reads the config", Opcode.IGET_OBJECT, code[ask - 3].opcode)
        assertEquals("$what: the config's type", VIEWER_CONFIG, (code[ask - 3].reference() as FieldReference).type)
        assertEquals("$what: of the object the pager went into", store.registerB, config.registerB)
        assertEquals("$what: into the free local", answer, config.registerA)
        assertEquals("$what: a missing config", Opcode.IF_EQZ, code[ask - 2].opcode)
        assertEquals("$what: a missing config goes to the hook with nothing", ask, viewer.targetOf(ask - 2))
        val source = code[ask - 1] as TwoRegisterInstruction
        assertEquals("$what: reads the source", Opcode.IGET_OBJECT, code[ask - 1].opcode)
        assertEquals("$what: the source's type", VIEWER_SOURCE, (code[ask - 1].reference() as FieldReference).type)
        assertEquals("$what: of the config", listOf(answer, answer), listOf(source.registerA, source.registerB))
        assertEquals("$what: on the stored pager, the source and what it went into", listOf(pager, answer, store.registerB), code[ask].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[ask + 1].opcode)
        assertEquals("$what: the answer's register", answer, (code[ask + 1] as OneRegisterInstruction).registerA)
        assertNotEquals("$what: the answer isn't the pager", pager, answer)
        assertTrue("$what: the answer fits an invoke", answer <= 15)
        assertEquals("$what: the skip", Opcode.IF_NEZ, code[ask + 2].opcode)
        assertEquals("$what: the skip lands past the hook", ask + 5, viewer.targetOf(ask + 2))
        assertEquals("$what: a 0", Opcode.CONST_4, code[ask + 3].opcode)
        assertEquals("$what: a 0", 0, (code[ask + 3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: the input turned off", setUserInput, code[ask + 4].referenceText())
        assertEquals("$what: the input turned off", listOf(pager, answer), code[ask + 4].arguments())
        assertEquals("$what: what came after the store", after, code[ask + 5].opcode)
        assertTrue("$what: a jump from outside lands inside the hook", (ask - 3..ask + 4).none { it != ask && it in viewer.jumpTargets() })
    }

    /** First in the setter: the hook on `this` and the value, and the answer back in the value's register. */
    private fun assertSetterAsks(what: String, setter: MutableMethod, self: Int) {
        val code = setter.code()
        assertEquals("$what: the setter asks first", USER_INPUT_HOOK, code[0].referenceText())
        assertEquals("$what: on this and the value", listOf(self, self + 1), code[0].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer replaces the value", self + 1, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: asks once", 1, code.count { it.referenceText() == USER_INPUT_HOOK })
        assertEquals("$what: the store still reads the value", self + 1, (code[2] as TwoRegisterInstruction).registerA)
    }

    /** First in a touch handler: the hook, its answer, a skip on 1 to the handler's own start, and a return of 0. */
    private fun assertPullAsks(what: String, touch: MutableMethod, free: Int) {
        val code = touch.code()
        val where = "$what: ${touch.name}"
        assertEquals("$where asks first", PULL_HOOK, code[0].referenceText())
        assertEquals("$where: on the layout", listOf(touch.implementation!!.registerCount - 2), code[0].arguments())
        assertEquals("$where: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$where: the answer's register", free, (code[1] as OneRegisterInstruction).registerA)
        assertTrue("$where: the answer's register is a local", free < touch.implementation!!.registerCount - 2)
        assertEquals("$where: the skip", Opcode.IF_NEZ, code[2].opcode)
        assertEquals("$where: the skip lands on the handler's start", 5, touch.targetOf(2))
        assertEquals("$where: a 0", Opcode.CONST_4, code[3].opcode)
        assertEquals("$where: a 0", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: returned", Opcode.RETURN, code[4].opcode)
        assertEquals("$where: returned", free, (code[4] as OneRegisterInstruction).registerA)
        assertEquals("$where: asks once", 1, code.count { it.referenceText() == PULL_HOOK })
    }

    private fun BytecodePatchContext.stop() = stopReelsScrolling(findReelsScrolling())

    private fun BytecodePatchContext.viewer(type: String): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == "onViewCreated" }

    private fun BytecodePatchContext.setter(): MutableMethod =
        mutableClassDefBy(VIEW_PAGER).methods.single { it.name == SET_USER_INPUT }

    private fun BytecodePatchContext.touch(name: String): MutableMethod =
        mutableClassDefBy(PULL_LAYOUT).methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(event) }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The Reels viewer, whose onViewCreated is handed a View, stores the pager in the pager object's
     * field and keeps using it; the pager object, which loads its view lookup's trace name and whose
     * static switch reads that field and turns its input back on; AndroidX's pager, whose setter
     * stores its value; and the pull-down layout with its two touch handlers.
     */
    private fun classes(
        lookups: Int = 1,
        pagerFields: Int = 1,
        switches: Int = 1,
        switchReadsPager: Boolean = true,
        switchSetsInput: Boolean = true,
        viewers: Int = 1,
        stores: Int = 1,
        busyLocals: Boolean = false,
        pagerConfigs: Int = 1,
        configClass: Boolean = true,
        configSources: Int = 1,
        pagerClass: Boolean = true,
        setterName: String = SET_USER_INPUT,
        setterStatic: Boolean = false,
        setterStores: Int = 1,
        setterLoops: Boolean = false,
        setterOverwrites: Boolean = false,
        layoutClass: Boolean = true,
        touches: List<String> = PULL_TOUCHES,
        touchStatic: Boolean = false,
        touchLocals: Int = 1,
        bridge: String = "public",
    ): List<ClassDef> {
        // The extension, whose bridge the patch writes.
        val classes = mutableListOf<ClassDef>(ImmutableClassDef.of(ExtensionDex.classDef(REEL_SCROLLING)))

        val lookup = method(pagerImpl, "getViewAtIndex", listOf("I"), "V", 1, body = """
            const-string v0, "$PAGER_VIEW_LOOKUP"
            invoke-static { v0 }, $trace
            return-void
        """)
        val lookupMethods = (0 until lookups).map { if (it == 0) lookup else ImmutableMethod.of(renamed(lookup, "getViewAtIndexAgain")) }
        val enable = method(pagerImpl, "enable", listOf(pagerImpl), "V", 2, static = true, body = """
            ${if (switchReadsPager) "iget-object v1, p0, $pagerField" else "const/4 v1, 0x0\ncheck-cast v1, $VIEW_PAGER"}
            if-eqz v1, :done
            const/4 v0, 0x1
            ${if (switchSetsInput) "invoke-virtual { v1, v0 }, $setUserInput" else "invoke-virtual { v1 }, $VIEW_PAGER->requestLayout()V"}
            :done
            return-void
        """)
        val enables = (0 until switches).map { if (it == 0) enable else ImmutableMethod.of(renamed(enable, "enableAgain")) }
        val pagers = (0 until pagerFields).map { if (it == 0) "pager" to VIEW_PAGER else "pager$it" to VIEW_PAGER }
        classes += classDef(
            pagerImpl, lookupMethods + enables, fields = pagers + (0 until pagerConfigs).map { "config$it" to VIEWER_CONFIG },
            flags = if (bridge == "private class") AccessFlags.FINAL.value else AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            privateFields = if (bridge == "private config") setOf("config0") else emptySet(),
        )
        if (configClass) classes += classDef(VIEWER_CONFIG, emptyList(), fields = (0 until configSources).map { "source$it" to VIEWER_SOURCE })

        // v2 holds the pager and is read after the store; v0 and v1 are free there, unless the
        // stand-in keeps every local busy.
        val viewer = { type: String ->
            method(type, "onViewCreated", listOf(view, bundle), "V", if (busyLocals) 3 else 4, body = """
                const/4 v0, 0x0
                iget-object v1, p0, $type->pagerImpl:$pagerImpl
                invoke-virtual { p1 }, $view->getRootView()$view
                move-result-object v2
                check-cast v2, $VIEW_PAGER
                ${(0 until stores).joinToString("\n") { "iput-object v2, v1, $pagerField" }}
                goto :after
                :after
                iput-object v2, p0, $type->kept:$view
                ${if (busyLocals) "invoke-static { v0, v1 }, Lfixture/Use;->both(Ljava/lang/Object;Ljava/lang/Object;)V" else "const/4 v3, 0x0\ninvoke-static { v3 }, Lfixture/Use;->one(I)V"}
                return-void
            """)
        }
        for (index in 0 until viewers) {
            val type = if (index == 0) viewerType else "Lfixture/OtherReelsViewer;"
            classes += classDef(type, listOf(viewer(type)), fields = listOf("pagerImpl" to pagerImpl, "kept" to view))
        }

        if (pagerClass) {
            val setter = method(VIEW_PAGER, setterName, listOf("Z"), "V", 1, static = setterStatic, body = if (setterStatic) "return-void" else """
                ${if (setterLoops) ":start" else ""}
                ${if (setterOverwrites) "const/4 p1, 0x1" else ""}
                ${(0 until setterStores).joinToString("\n") { if (it == 0) "iput-boolean p1, p0, $inputField" else "iput-boolean p1, p0, $VIEW_PAGER->other:Z" }}
                iget-object v0, p0, $VIEW_PAGER->provider:Ljava/lang/Object;
                ${if (setterLoops) "if-eqz v0, :start" else ""}
                return-void
            """)
            val requestLayout = method(VIEW_PAGER, "requestLayout", emptyList(), "V", 0, body = "return-void")
            classes += classDef(
                VIEW_PAGER, listOf(setter, requestLayout),
                fields = listOf("userInput" to "Z", "other" to "Z", "provider" to "Ljava/lang/Object;"),
            )
        }

        if (layoutClass) {
            val handlers = touches.map { name ->
                val static = touchStatic && name == "onInterceptTouchEvent"
                method(PULL_LAYOUT, name, listOf(event), "Z", touchLocals, static = static, body = if (static) """
                    const/4 v0, 0x0
                    return v0
                """ else if (touchLocals == 0) """
                    invoke-virtual { p1 }, $event->getAction()I
                    move-result p1
                    return p1
                """ else """
                    const/4 v0, 0x0
                    invoke-virtual { p1 }, $event->getAction()I
                    move-result v0
                    if-nez v0, :moving
                    const/4 v0, 0x1
                    :moving
                    return v0
                """)
            }
            classes += classDef(PULL_LAYOUT, handlers, fields = listOf("refreshing" to "Z"))
        }
        return classes
    }

    private fun renamed(method: Method, name: String): Method = ImmutableMethod(
        method.definingClass, name, method.parameters, method.returnType, method.accessFlags, null, null, method.implementation,
    )

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(
        type: String,
        methods: List<Method>,
        fields: List<Pair<String, String>> = emptyList(),
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        privateFields: Set<String> = emptySet(),
    ): ClassDef =
        ImmutableClassDef(
            type, flags, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) ->
                ImmutableField(type, name, fieldType, if (name in privateFields) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value, null, null, null)
            },
            methods,
        )

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers, literal and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        if (this@describe is NarrowLiteralInstruction) append(" #$narrowLiteral")
        referenceText()?.let { append(" $it") }
    }
}

/**
 * The types a slice of [bundle] needs for [reelsPager] and [reelsPagerSetup]: the classes loading
 * [PAGER_VIEW_LOOKUP] (the pager and any string pool it asks) and every class storing the pager's
 * [VIEW_PAGER] field.
 */
internal fun reelsPagerHolders(bundle: File): Set<String> {
    val loading = FixtureDex.classesLoading(bundle, PAGER_VIEW_LOOKUP)
    val fields = loading.flatMap { holder ->
        holder.fields.filter { it.type == VIEW_PAGER && !AccessFlags.STATIC.isSet(it.accessFlags) }.map { "${holder.type}->${it.name}:$VIEW_PAGER" }
    }.toSet()
    val owners = fields.map { it.substringBefore("->") }.toSet()
    val storers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in owners } }) { method ->
        method.implementation?.instructions?.any {
            it.opcode == Opcode.IPUT_OBJECT && ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString() in fields
        } == true
    }.map { it.definingClass }
    return loading.map { it.type }.toSet() + storers
}

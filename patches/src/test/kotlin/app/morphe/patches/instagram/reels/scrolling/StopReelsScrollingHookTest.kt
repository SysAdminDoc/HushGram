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
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val stored = "expected $viewerType->onViewCreated to store $pagerField once, found"
        val cases = listOf(
            classes(switches = 0) to "expected exactly one Reels pager switch holding \"$ENABLE_SCROLLING\" in this Instagram build, found none",
            classes(switches = 2) to "expected exactly one Reels pager switch holding \"$ENABLE_SCROLLING\" in this Instagram build, found $pagerImpl",
            classes(switchReadsPager = false) to "expected $pagerImpl->enable to read one $VIEW_PAGER field of its class, found 0",
            classes(switchSetsInput = false) to "$pagerImpl->enable doesn't call $VIEW_PAGER->$SET_USER_INPUT",
            classes(viewers = 0) to "expected exactly one Reels pager setup holding \"$PAGER_SETUP\" in this Instagram build, found none",
            classes(viewers = 2) to "expected exactly one Reels pager setup holding \"$PAGER_SETUP\" in this Instagram build, found Lfixture/",
            classes(stores = 0) to "$stored 0",
            classes(stores = 2) to "$stored 2",
            classes(busyLocals = true) to "$viewerType->onViewCreated has no local register up to v15 that nothing reads after storing its pager",
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

    private fun builds(bundle: File, label: String) {
        val holders = (FixtureDex.classesHolding(bundle, PAGER_SETUP) + FixtureDex.classesHolding(bundle, ENABLE_SCROLLING))
            .map { it.type }.toSet()
        val types = holders + VIEW_PAGER + PULL_LAYOUT
        for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
            val what = "${label} ($read)"
            val classes = classesOf(bundle, types).values
            assertEquals("$what: the viewer, the pager, AndroidX's pager and the layout", types, classes.map { it.type }.toSet())
            val context = PatchContexts.of(classes)
            val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                .filter { it.implementation != null }.associateWith(::NeutralNativePath)

            context.stop()

            val asking = holders.flatMap { type ->
                context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.referenceText() == PAGER_HOOK } }
            }
            assertEquals("$what: methods asking about the pager", 1, asking.size)
            val viewer = asking.single()
            val code = viewer.code()
            val ask = code.indexOfFirst { it.referenceText() == PAGER_HOOK }
            val store = code[ask - 1]
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
                        PAGER_HOOK, PULL_HOOK -> (at until at + 5).toList()
                        USER_INPUT_HOOK -> (at until at + 2).toList()
                        else -> emptyList()
                    }
                }.toSet()
                original.assertPreserved("$what ${method.name}", method, added)
            }
        }
    }

    /** After the store: the hook on the pager, its answer, a skip on 1, and the pager's input turned off with a 0. */
    private fun assertPagerHeld(what: String, viewer: MutableMethod, pager: Int, answer: Int, after: Opcode) {
        val code = viewer.code()
        val asks = code.indices.filter { code[it].referenceText() == PAGER_HOOK }
        assertEquals("$what: asks about the pager", 1, asks.size)
        val ask = asks.single()
        assertEquals("$what: right after the store", Opcode.IPUT_OBJECT, code[ask - 1].opcode)
        assertEquals("$what: on the stored pager", listOf(pager), code[ask].arguments())
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
        assertTrue("$what: a jump lands inside the hook", (ask..ask + 4).none { it in viewer.jumpTargets() })
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
     * The Reels viewer, whose onViewCreated traces the pager's setup, stores the pager in the pager
     * object's field and keeps using it; the pager object, whose switch reads that field and turns
     * its input back on; AndroidX's pager, whose setter stores its value; and the pull-down layout
     * with its two touch handlers.
     */
    private fun classes(
        switches: Int = 1,
        switchReadsPager: Boolean = true,
        switchSetsInput: Boolean = true,
        viewers: Int = 1,
        stores: Int = 1,
        busyLocals: Boolean = false,
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
    ): List<ClassDef> {
        val classes = mutableListOf<ClassDef>()

        val enable = method(pagerImpl, "enable", emptyList(), "V", 2, body = """
            const-string v0, "$ENABLE_SCROLLING"
            invoke-static { v0 }, $trace
            ${if (switchReadsPager) "iget-object v1, p0, $pagerField" else "const/4 v1, 0x0\ncheck-cast v1, $VIEW_PAGER"}
            if-eqz v1, :done
            const/4 v0, 0x1
            ${if (switchSetsInput) "invoke-virtual { v1, v0 }, $setUserInput" else "invoke-virtual { v1 }, $VIEW_PAGER->requestLayout()V"}
            :done
            return-void
        """)
        val enables = (0 until switches).map { if (it == 0) enable else ImmutableMethod.of(renamed(enable, "enableAgain")) }
        classes += classDef(pagerImpl, enables, fields = listOf("pager" to VIEW_PAGER))

        // v2 holds the pager and is read after the store; v0 and v1 are free there, unless the
        // stand-in keeps every local busy.
        val viewer = { type: String ->
            method(type, "onViewCreated", listOf(view, bundle), "V", if (busyLocals) 3 else 4, body = """
                const-string v0, "$PAGER_SETUP"
                invoke-static { v0 }, $trace
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

    private fun classDef(type: String, methods: List<Method>, fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
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

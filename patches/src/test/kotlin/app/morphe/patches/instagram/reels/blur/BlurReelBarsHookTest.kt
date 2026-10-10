/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.blur

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.reels.scrolling.ENABLE_SCROLLING
import app.morphe.patches.instagram.reels.scrolling.PAGER_SETUP
import app.morphe.patches.instagram.reels.scrolling.SET_USER_INPUT
import app.morphe.patches.instagram.reels.scrolling.VIEW_PAGER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurReelBarsHookTest {
    private val viewerType = "Lfixture/ReelsViewer;"
    private val pagerImpl = "Lfixture/ReelsPager;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val view = "Landroid/view/View;"
    private val bundle = "Landroid/os/Bundle;"
    private val pagerField = "$pagerImpl->pager:$VIEW_PAGER"
    private val setUserInput = "$VIEW_PAGER->$SET_USER_INPUT(Z)V"

    /** The hook the patch writes is in the ReelBlurBars the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_BLUR_BARS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BLUR_PAGER_HOOK is not in the extension: $declared", BLUR_PAGER_HOOK.substringAfter("->") in declared)
    }

    /** The viewer hands over its pager right after storing it, once, and nothing else in it changes. */
    @Test
    fun theViewerHandsOverItsPagerRightAfterTheStore() {
        val context = PatchContexts.of(classes())
        val original = context.viewer(viewerType).code().map { it.describe() }

        context.blur()

        val viewer = context.viewer(viewerType)
        assertHandedOver("the stand-in", viewer, pager = 2)
        val now = viewer.code().map { it.describe() }
        val ask = viewer.code().indexOfFirst { it.referenceText() == BLUR_PAGER_HOOK }
        assertEquals("only the one invoke is new", original, now.filterIndexed { at, _ -> at != ask })
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
            classes(viewers = 0) to "expected exactly one Reels viewer onViewCreated holding \"$PAGER_SETUP\" in this Instagram build, found none",
            classes(viewers = 2) to "expected exactly one Reels viewer onViewCreated holding \"$PAGER_SETUP\" in this Instagram build, found Lfixture/",
            classes(stores = 0) to "$stored 0",
            classes(stores = 2) to "$stored 2",
            classes(jumpsToTheInstructionAfter = true) to "something in $viewerType->onViewCreated jumps to the instruction after its store of $pagerField",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.blur() }
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
     * In each declared build the Reels viewer hands over its pager once right after storing it, whether
     * the classes are copied or read as the patcher reads an APK, and every other instruction of it
     * keeps its place and its branches.
     */
    @Test
    fun eachDeclaredBuildHoldsItsReelsPager() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = (FixtureDex.classesHolding(bundle, PAGER_SETUP) + FixtureDex.classesHolding(bundle, ENABLE_SCROLLING))
                    .map { it.type }.toSet()
                val types = holders + VIEW_PAGER
                for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                    val what = "${bundle.name} ($read)"
                    val classes = classesOf(bundle, types).values
                    assertEquals("$what: the viewer, the pager's owner and AndroidX's pager", types, classes.map { it.type }.toSet())
                    val context = PatchContexts.of(classes)
                    val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                        .filter { it.implementation != null }.associateWith(::NeutralNativePath)

                    context.blur()

                    val asking = holders.flatMap { type ->
                        context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.referenceText() == BLUR_PAGER_HOOK } }
                    }
                    assertEquals("$what: methods handing over the pager", listOf("onViewCreated"), asking.map { it.name })
                    val viewer = asking.single()
                    val code = viewer.code()
                    val ask = code.indexOfFirst { it.referenceText() == BLUR_PAGER_HOOK }
                    val store = code[ask - 1]
                    assertEquals("$what: the store", Opcode.IPUT_OBJECT, store.opcode)
                    assertEquals("$what: the pager's type", VIEW_PAGER, (store.reference() as FieldReference).type)
                    assertHandedOver(what, viewer, (store as TwoRegisterInstruction).registerA)
                    for ((method, original) in originals) {
                        val added = method.code().indices.filter { method.code()[it].referenceText() == BLUR_PAGER_HOOK }.toSet()
                        original.assertPreserved("$what ${method.name}", method, added)
                    }
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** One call of the hook, right after the pager's store, on the register that holds the pager, and no jump into it. */
    private fun assertHandedOver(what: String, viewer: MutableMethod, pager: Int) {
        val code = viewer.code()
        val asks = code.indices.filter { code[it].referenceText() == BLUR_PAGER_HOOK }
        assertEquals("$what: hands over the pager", 1, asks.size)
        val ask = asks.single()
        assertEquals("$what: right after the store", Opcode.IPUT_OBJECT, code[ask - 1].opcode)
        assertEquals("$what: on the stored pager", listOf(pager), code[ask].arguments())
        assertTrue("$what: the pager's register fits an invoke", pager <= 15)
        assertTrue("$what: a jump lands inside the hook", ask !in viewer.jumpTargets())
    }

    private fun BytecodePatchContext.blur() = applyBlurReelBars(findReelPagerStore())

    private fun BytecodePatchContext.viewer(type: String): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == "onViewCreated" }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The Reels viewer, whose onViewCreated traces the pager's setup and stores the pager in the pager
     * object's field; the pager object, whose switch reads that field and turns its input back on;
     * and AndroidX's pager with its setter.
     */
    private fun classes(
        switches: Int = 1,
        switchReadsPager: Boolean = true,
        switchSetsInput: Boolean = true,
        viewers: Int = 1,
        stores: Int = 1,
        jumpsToTheInstructionAfter: Boolean = false,
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

        // v2 holds the pager and is read after the store.
        val viewer = { type: String ->
            method(type, "onViewCreated", listOf(view, bundle), "V", 4, body = """
                const-string v0, "$PAGER_SETUP"
                invoke-static { v0 }, $trace
                iget-object v1, p0, $type->pagerImpl:$pagerImpl
                invoke-virtual { p1 }, $view->getRootView()$view
                move-result-object v2
                check-cast v2, $VIEW_PAGER
                ${if (jumpsToTheInstructionAfter) "if-eqz v1, :after" else "goto :start"}
                :start
                ${(0 until stores).joinToString("\n") { "iput-object v2, v1, $pagerField" }}
                :after
                iput-object v2, p0, $type->kept:$view
                return-void
            """)
        }
        for (index in 0 until viewers) {
            val type = if (index == 0) viewerType else "Lfixture/OtherReelsViewer;"
            classes += classDef(type, listOf(viewer(type)), fields = listOf("pagerImpl" to pagerImpl, "kept" to view))
        }

        val setter = method(VIEW_PAGER, SET_USER_INPUT, listOf("Z"), "V", 1, body = """
            iput-boolean p1, p0, $VIEW_PAGER->userInput:Z
            return-void
        """)
        val requestLayout = method(VIEW_PAGER, "requestLayout", emptyList(), "V", 0, body = "return-void")
        classes += classDef(VIEW_PAGER, listOf(setter, requestLayout), fields = listOf("userInput" to "Z"))
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
        body: String,
    ): Method {
        val total = registers + 1 + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, AccessFlags.PUBLIC.value, null, null,
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

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        referenceText()?.let { append(" $it") }
    }
}

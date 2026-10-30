/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
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
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long press on the Instagram logo at the top of Home opens HushGram settings. Instagram gives
 * the title view holding the logo a long press of its own in the method that draws Home's header,
 * and the settings patch sends that one call to SettingsEntry, which wraps Instagram's listener.
 * A build where that call isn't there exactly once keeps its code as it was.
 */
class LogoLongPressTest {
    private val title = "$LOGO_BAR->title:$LOGO_TITLE"
    private val button = "$LOGO_BAR->button:Landroid/widget/ImageView;"
    private val setLongPress = "Landroid/view/View;->setOnLongClickListener(Landroid/view/View\$OnLongClickListener;)V"

    /** The stand-in the patch calls is in the SettingsEntry the bundle ships, public and static. */
    @Test
    fun theStandInIsInTheExtension() {
        val declared = ExtensionDex.classDef(ENTRY).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$LOGO_LONG_PRESS_STAND_IN is not in the extension: $declared", LOGO_LONG_PRESS_STAND_IN.substringAfter("->") in declared)
        assertTrue("$MERGED_LOGO_STAND_IN is not in the extension: $declared", MERGED_LOGO_STAND_IN.substringAfter("->") in declared)
    }

    /**
     * Home's newer header gives its logo no long press. The logo view goes to the stand-in right
     * after the header keeps it, on the register it was kept from, and nothing of Instagram's moves.
     */
    @Test
    fun theNewerHeadersLogoGoesToTheStandInOnceKept() {
        val context = PatchContexts.of(listOf(mergedBar(mergedInit())))

        assertTrue(context.hookMergedLogoLongPress())

        val code = context.mergedInitMethod().code()
        val hooks = code.indices.filter { code[it].referenceText() == MERGED_LOGO_STAND_IN }
        assertEquals("one stand-in, after the store", listOf(4), hooks)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[4].opcode)
        assertEquals("the kept logo", listOf(1), code[4].arguments())
        assertEquals("the logo's store", Opcode.IPUT_OBJECT, code[3].opcode)
        assertEquals("the other view's store stays", Opcode.IPUT_OBJECT, code[5].opcode)
        assertEquals("one added", 7, code.size)
    }

    /**
     * No newer header, one that keeps no logo view, or one that keeps it twice: the patch says how
     * many it found, answers false and changes nothing.
     */
    @Test
    fun withoutExactlyOneLogoStoreTheNewerHeaderIsLeftAlone() {
        val noLogo = """
            const/4 v1, 0x0
            iput-object v1, p0, $mergedOther
            return-void
        """
        val twice = """
            const/4 v1, 0x0
            iput-object v1, p0, $mergedLogo
            iput-object v1, p0, $mergedLogo
            return-void
        """
        val cases = listOf(
            listOf(bar(draw())) to 0,
            listOf(mergedBar(mergedInit(noLogo))) to 0,
            listOf(mergedBar(mergedInit(twice))) to 2,
        )
        for ((classes, found) in cases) {
            val context = PatchContexts.of(classes)
            var hooked = true
            val warnings = PatchLogCapture.warnings { hooked = context.hookMergedLogoLongPress() }
            assertFalse("$found stores: hooked anyway", hooked)
            assertTrue("$found stores: $warnings", warnings.single().contains("$found places keep the Instagram logo in Home's newer header"))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.name }
                for (method in original.methods) {
                    assertEquals(
                        "$found stores: ${original.type}->${method.name} changed",
                        method.code().map { it.describe() }, now.getValue(method.name).code().map { it.describe() },
                    )
                }
            }
        }
    }

    /**
     * The title view's long press goes to the stand-in on the same registers, in the call's own
     * place, so a branch landing on the call lands on the stand-in. The long press the header gives
     * a button stays Instagram's.
     */
    @Test
    fun theTitlesLongPressGoesToTheStandIn() {
        val context = PatchContexts.of(listOf(bar(draw())))

        assertTrue(context.hookLogoLongPress())

        val code = context.drawMethod().code()
        val hooks = code.indices.filter { code[it].referenceText() == LOGO_LONG_PRESS_STAND_IN }
        assertEquals("one stand-in", listOf(5), hooks)
        assertEquals(Opcode.INVOKE_STATIC, code[5].opcode)
        assertEquals("the title, then Instagram's listener", listOf(1, 0), code[5].arguments())
        assertEquals("the button's long press stays", setLongPress, code[4].referenceText())
        assertEquals("the button's receiver", listOf(2, 0), code[4].arguments())
        assertEquals("the branch still lands on the call's place", setOf(5), context.drawMethod().jumpTargets())
        assertEquals("nothing added", 7, code.size)
    }

    /** A title view the method reads some other way and casts counts too. */
    @Test
    fun aTitleCastBeforeTheCallCounts() {
        val body = """
            invoke-virtual { p0 }, $LOGO_BAR->getChildAt0()Landroid/view/View;
            move-result-object v1
            check-cast v1, $LOGO_TITLE
            const/4 v0, 0x0
            invoke-virtual { v1, v0 }, $setLongPress
            return-void
        """
        val context = PatchContexts.of(listOf(bar(draw(body))))

        assertTrue(context.hookLogoLongPress())

        val code = context.drawMethod().code()
        assertEquals(LOGO_LONG_PRESS_STAND_IN, code[4].referenceText())
        assertEquals(listOf(1, 0), code[4].arguments())
    }

    /**
     * No header, a header that gives only a button a long press, one that writes over the title's
     * register before the call, or one that gives the title a long press in two places: the patch
     * says how many it found, answers false and changes nothing, so the settings patch goes on.
     */
    @Test
    fun withoutExactlyOneSiteTheLogoIsLeftAlone() {
        val onlyButton = """
            iget-object v2, p0, $button
            const/4 v0, 0x0
            invoke-virtual { v2, v0 }, $setLongPress
            return-void
        """
        val overwritten = """
            iget-object v1, p0, $title
            move-object v1, p1
            const/4 v0, 0x0
            invoke-virtual { v1, v0 }, $setLongPress
            return-void
        """
        val cases = listOf(
            listOf(classDef("Lfixture/Other;", listOf(method("Lfixture/Other;", "draw", emptyList(), "V", 0, body = "return-void")))) to 0,
            listOf(bar(draw(onlyButton))) to 0,
            listOf(bar(draw(overwritten))) to 0,
            listOf(bar(draw(), draw(name = "drawAgain"))) to 2,
        )
        for ((classes, found) in cases) {
            val context = PatchContexts.of(classes)
            var hooked = true
            val warnings = PatchLogCapture.warnings { hooked = context.hookLogoLongPress() }
            assertFalse("$found sites: hooked anyway", hooked)
            assertTrue("$found sites: $warnings", warnings.single().contains("$found places give the Instagram logo"))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.name }
                for (method in original.methods) {
                    assertEquals(
                        "$found sites: ${original.type}->${method.name} changed",
                        method.code().map { it.describe() }, now.getValue(method.name).code().map { it.describe() },
                    )
                }
            }
        }
    }

    /**
     * In each declared build, Home's header gives its title view a long press in one place, the
     * method drawing the header from a state, on the title view read from its field. The patch sends
     * that call to the stand-in on the same registers and leaves the rest of Instagram's method as
     * it was, the long presses the header gives its other views included.
     */
    @Test
    fun eachDeclaredBuildSendsTheLogosLongPressToTheStandIn() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                hooksMerged(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The same on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildSendsTheLogosLongPressToTheStandInToo() {
        for (base in Fixtures.otherBuilds()) {
            hooks(base, base.parentFile.name)
            hooksMerged(base, base.parentFile.name)
        }
    }

    /**
     * The newer header keeps its logo once, in its constructor, from the view its layout finder
     * answered (the field's name and the finder differ between builds). The stand-in goes right
     * after the store, on the stored register, and the rest of the constructor stays as it was.
     */
    private fun hooksMerged(bundle: File, label: String) {
        val bar = FixtureDex.classes(bundle, setOf(MERGED_LOGO_BAR)).values.single()
        val context = PatchContexts.of(listOf(bar))
        val store = context.mergedLogoStores().singleOrNull()
            ?: throw AssertionError("$label: expected the newer header to keep its logo once, found ${context.mergedLogoStores().size}")
        val stock = bar.methods.single { it.key() == store.method.key() }
        assertEquals("$label: kept in the constructor", "<init>", stock.name)
        val kept = (stock.code()[store.index] as OneRegisterInstruction).registerA
        val original = NeutralNativePath(stock)

        assertTrue("$label: not hooked", context.hookMergedLogoLongPress())

        val after = context.mutableClassDefBy(MERGED_LOGO_BAR).methods.single { it.key() == stock.key() }
        val code = after.code()
        val hook = store.index + 1
        assertEquals("$label: the stand-in right after the store", MERGED_LOGO_STAND_IN, code[hook].referenceText())
        assertEquals("$label: on the kept logo", listOf(kept), code[hook].arguments())
        assertEquals("$label: one stand-in", 1, code.count { it.referenceText() == MERGED_LOGO_STAND_IN })
        original.assertPreserved(label, after, added = setOf(hook))
    }

    private fun hooks(bundle: File, label: String) {
        val bar = FixtureDex.classes(bundle, setOf(LOGO_BAR)).values.single()
        val context = PatchContexts.of(listOf(bar))
        val site = context.logoLongPressSites().singleOrNull()
            ?: throw AssertionError("$label: expected one long press on the title view, found ${context.logoLongPressSites().size}")
        val stock = bar.methods.single { it.key() == site.method.key() }
        assertFalse("$label: the header's draw is static", AccessFlags.STATIC.isSet(stock.accessFlags))
        assertEquals("$label: the header's draw takes the session second", "Lcom/instagram/common/session/UserSession;",
            stock.parameterTypes.getOrNull(1)?.toString())
        val stockCode = stock.code()
        val call = stockCode[site.index]
        assertEquals("$label: Instagram's call", setLongPress, call.referenceText())
        val receiver = call.arguments().first()
        val read = stockCode.subList(0, site.index).last { (it as? OneRegisterInstruction)?.registerA == receiver && it.opcode.setsRegister() }
        assertEquals("$label: the title view read for it", Opcode.IGET_OBJECT, read.opcode)
        val field = (read as ReferenceInstruction).reference as FieldReference
        assertEquals("$label: the field holding the title view", LOGO_BAR to LOGO_TITLE, field.definingClass to field.type)
        val longPresses = bar.methods.sumOf { method -> method.code().count { it.referenceText() == setLongPress } }
        val original = NeutralNativePath(stock)

        assertTrue("$label: not hooked", context.hookLogoLongPress())

        val after = context.mutableClassDefBy(LOGO_BAR).methods.single { it.key() == stock.key() }
        val code = after.code()
        assertEquals("$label: the stand-in in the call's place", LOGO_LONG_PRESS_STAND_IN, code[site.index].referenceText())
        assertEquals("$label: invoke-static", Opcode.INVOKE_STATIC, code[site.index].opcode)
        assertEquals("$label: the same registers", call.arguments(), code[site.index].arguments())
        assertEquals("$label: one stand-in", 1, code.count { it.referenceText() == LOGO_LONG_PRESS_STAND_IN })
        original.assertPreserved(label, after, added = emptySet(), substituted = setOf(site.index))
        val left = context.mutableClassDefBy(LOGO_BAR).methods.sumOf { method -> method.code().count { it.referenceText() == setLongPress } }
        assertEquals("$label: the header's other long presses stay", longPresses - 1, left)
    }

    // ---- stand-ins shaped like Instagram 450's header -----------------------------------------

    /**
     * The header's draw: reads the title view, gives a button a long press on one branch, then
     * gives the title view its long press, which the branch lands on.
     */
    private fun draw(body: String = stockDraw, name: String = "draw"): Method =
        method(LOGO_BAR, name, listOf("Ljava/lang/Object;"), "V", 3, body = body)

    private val stockDraw get() = """
        iget-object v1, p0, $title
        const/4 v0, 0x0
        if-eqz p1, :set
        iget-object v2, p0, $button
        invoke-virtual { v2, v0 }, $setLongPress
        :set
        invoke-virtual { v1, v0 }, $setLongPress
        return-void
    """

    private fun bar(vararg methods: Method): ClassDef = classDef(LOGO_BAR, methods.toList(), "Landroid/widget/FrameLayout;")

    private fun BytecodePatchContext.drawMethod(): MutableMethod = mutableClassDefBy(LOGO_BAR).methods.single { it.name == "draw" }

    private val mergedLogo = "$MERGED_LOGO_BAR->logo:$MERGED_LOGO_VIEW"
    private val mergedOther = "$MERGED_LOGO_BAR->tabs:Landroid/view/View;"

    /** The newer header's constructor: finds the logo in its layout, keeps it, then keeps another view. */
    private fun mergedInit(body: String = stockMergedInit): Method =
        method(MERGED_LOGO_BAR, "<init>", listOf("Landroid/content/Context;"), "V", 2, body = body)

    private val stockMergedInit get() = """
        invoke-virtual { p0 }, $MERGED_LOGO_BAR->findLogo()Landroid/view/View;
        move-result-object v1
        check-cast v1, $MERGED_LOGO_VIEW
        iput-object v1, p0, $mergedLogo
        iput-object v1, p0, $mergedOther
        return-void
    """

    private fun mergedBar(vararg methods: Method): ClassDef = classDef(MERGED_LOGO_BAR, methods.toList(), "Landroid/widget/LinearLayout;")

    private fun BytecodePatchContext.mergedInitMethod(): MutableMethod = mutableClassDefBy(MERGED_LOGO_BAR).methods.single { it.name == "<init>" }

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

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;"): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, emptyList(), methods)

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")") + returnType

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

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

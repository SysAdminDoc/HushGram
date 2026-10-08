/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenDeveloperOptionsTest {
    private val options = "Lfixture/Options;"
    private val press = "Lfixture/HomePress;"
    private val context = "Landroid/content/Context;"
    private val activity = "Landroidx/fragment/app/FragmentActivity;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val mainActivity = "Lcom/instagram/mainactivity/InstagramMainActivity;"

    /**
     * The long press asks open() first, and on a yes hands its main activity, twice, and its session
     * to the opener's instance and returns true. On a no it runs from its own first instruction.
     */
    @Test
    fun theLongPressAsksFirstAndOpensTheOptions() {
        val patch = PatchContexts.of(classes())

        patch.openOnLongPress(patch.findOptionsOpener())

        val method = patch.longPress()
        val code = method.code()
        val self = method.implementation!!.registerCount - 2
        assertEquals("the question", OPEN_DEVELOPER_OPTIONS, code[0].referenceText())
        assertEquals(Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals("this", self, (code[3] as TwoRegisterInstruction).registerB)
        assertEquals("the activity", "$press->activity:$mainActivity", code[4].referenceText())
        assertEquals("the session", "$press->session:$session", code[5].referenceText())
        assertEquals("the opener's instance", "$options->instance:$options", code[6].referenceText())
        val call = code[7] as Instruction35c
        assertEquals("the opener", "$options->open($context$activity$session)V", call.referenceText())
        assertEquals("the call's registers", listOf(3, 1, 1, 2), listOf(call.registerC, call.registerD, call.registerE, call.registerF))
        assertEquals(Opcode.CONST_4, code[8].opcode)
        assertEquals(Opcode.RETURN, code[9].opcode)
        assertEquals(0, (code[9] as OneRegisterInstruction).registerA)
        assertEquals("the press as it was", "$press->flag:I", code[10].referenceText())
    }

    /** A build missing a piece, or with two of one, says which and leaves the press alone. */
    @Test
    fun whatItCantPlaceItLeavesAlone() {
        val cases = mapOf(
            "no opener" to classes(error = false),
            "no instance" to classes(instance = false),
            "no press" to classes(pressStrings = listOf("click")),
            "two presses" to classes() + press("Lfixture/OtherPress;"),
            "no session" to classes(sessionField = false),
            "too few locals" to classes(registers = 4),
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes)

            val failure = runCatching { patch.openOnLongPress(patch.findOptionsOpener()) }.exceptionOrNull()

            assertTrue(case, failure?.message?.startsWith("Open developer options: ") == true)
            assertTrue(case, patch.longPress().code().none { it.referenceText() == OPEN_DEVELOPER_OPTIONS })
        }
    }

    /**
     * With no override reader to be found, the long press and the MetaConfig and Whitehat entries go
     * in all the same. No reader or writer stub is filled, both of their statuses stay off, and the
     * patch log says what was left out.
     */
    @Test
    fun aReaderThatMovedLeavesTheLongPressAndTheEntriesIn() {
        val patch = PatchContexts.of(classes() + extension())
        val stock = patch.bridge()

        val warnings = PatchLogCapture.warnings { patch.openDeveloperOptions(standInEditor, whitehat) }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("Open developer options: expected one signed-in override diagnostics, found 0. "))
        assertTrue(warnings.single(), warnings.single().endsWith(" without Export, Validate and Import."))
        assertEquals("asks first", OPEN_DEVELOPER_OPTIONS, patch.longPress().code()[0].referenceText())
        val filled = patch.bridge().filter { (stub, code) -> stock[stub] != code }.keys
        assertEquals(setOf("openOverridesNative(Ljava/lang/Object;)I", "openWhitehatNative(Ljava/lang/Object;)I"), filled)
        assertEquals(1, patch.answer("developerOptions"))
        assertEquals(0, patch.answer(OVERRIDE_EXCHANGE_STATUS))
        assertEquals(0, patch.answer(OVERRIDE_IMPORT_STATUS))
    }

    /** An opener that moved still stops the patch before a stub, the press or a status changes. */
    @Test
    fun anOpenerThatMovedStillStopsThePatchBeforeAnythingChanges() {
        val patch = PatchContexts.of(classes(error = false) + extension())
        val stock = patch.bridge()

        PatchLogCapture.warnings { assertThrows(PatchException::class.java) { patch.openDeveloperOptions(standInEditor, whitehat) } }

        assertEquals(stock, patch.bridge())
        assertTrue(patch.longPress().code().none { it.referenceText() == OPEN_DEVELOPER_OPTIONS })
        assertEquals(0, patch.answer("developerOptions"))
        assertEquals(0, patch.answer(OVERRIDE_EXCHANGE_STATUS))
        assertEquals(0, patch.answer(OVERRIDE_IMPORT_STATUS))
    }

    /** In each declared build, the one long press with both strings asks first and opens the one opener. */
    @Test
    fun eachDeclaredBuildOpensTheOptionsOnALongPress() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    if (OPTIONS_ERROR !in dex.stringSection && LONG_PRESS_STRINGS.any { it !in dex.stringSection }) return@forEach
                    for (classDef in dex.classes) {
                        val wanted = classDef.methods.any { method ->
                            val strings = method.strings()
                            OPTIONS_ERROR in strings || (method.name == "onLongClick" && strings.containsAll(LONG_PRESS_STRINGS))
                        }
                        if (wanted) classes += ImmutableClassDef.of(classDef)
                    }
                }
                val patch = PatchContexts.of(classes)

                val opener = patch.findOptionsOpener()
                patch.openOnLongPress(opener)

                val handler = classes.single { classDef ->
                    classDef.methods.any { it.name == "onLongClick" && it.strings().containsAll(LONG_PRESS_STRINGS) }
                }
                val code = patch.classDefBy(handler.type).methods.single { it.name == "onLongClick" }.code()
                assertEquals("${bundle.name}: asks first", OPEN_DEVELOPER_OPTIONS, code[0].referenceText())
                assertTrue("${bundle.name}: the main activity", code[4].referenceText()!!.endsWith(":$mainActivity"))
                assertTrue("${bundle.name}: the session", code[5].referenceText()!!.endsWith(":$session"))
                assertEquals("${bundle.name}: the opener", opener.open, code[7].referenceText())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun BytecodePatchContext.longPress(): Method = classDefBy(press).methods.single { it.name == "onLongClick" }

    /** An editor and a Whitehat screen as the finders hand them over; only their smali has to assemble. */
    private val standInEditor = OverrideEditor(
        "Lcom/instagram/base/activity/IgFragmentActivity;->session()Lfixture/BaseSession;",
        "Lfixture/Editor;->factory(Lcom/instagram/base/activity/IgFragmentActivity;Ljava/lang/Object;)Ljava/lang/Object;",
        "Lfixture/EditorFragment;",
        "Lfixture/Editor;->present(Landroidx/fragment/app/Fragment;Ljava/lang/Object;)V",
    )
    private val whitehat = "Lfixture/WhitehatScreen;"

    /** The bridge with every stub, and the status class, as the bundle ships them. */
    private fun extension() = listOf(ExtensionDex.classDef(OVERRIDE_BRIDGE), ExtensionDex.classDef(SETTINGS_STATUS))

    /** Each bridge stub's code, as opcodes and what they reference. */
    private fun BytecodePatchContext.bridge(): Map<String, List<String>> = classDefBy(OVERRIDE_BRIDGE).methods.associate { method ->
        "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" to
            method.code().map { "${it.opcode} ${it.referenceText().orEmpty()}" }
    }

    /** What a SettingsStatus method answers: 1 once the patch switches it on, 0 as shipped. */
    private fun BytecodePatchContext.answer(status: String): Int =
        (classDefBy(SETTINGS_STATUS).methods.single { it.name == status }.code().first() as NarrowLiteralInstruction).narrowLiteral

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.strings(): List<String> = code().mapNotNull {
        if (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) ((it as ReferenceInstruction).reference as StringReference).string else null
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The developer options opener, with its instance, its static holding the error key and its open
     * method, and Home's long press handler keeping the main activity and the session.
     */
    private fun classes(
        error: Boolean = true,
        instance: Boolean = true,
        pressStrings: List<String> = LONG_PRESS_STRINGS,
        sessionField: Boolean = true,
        registers: Int = 9,
    ): List<ClassDef> {
        val openerMethods = listOf(
            method(options, "show", listOf(context, activity, session, "Ljava/util/concurrent/Callable;"), "V", 5,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                (if (error) "const-string v0, \"$OPTIONS_ERROR\"\n" else "") + "return-void"),
            method(options, "open", listOf(context, activity, session), "V", 4, AccessFlags.PUBLIC.value, "return-void"),
        )
        val openerFields = if (instance) {
            listOf(ImmutableField(options, "instance", options, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null))
        } else {
            emptyList()
        }
        return listOf(
            ImmutableClassDef(options, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, openerFields, openerMethods),
            press(press, pressStrings, sessionField, registers),
        )
    }

    private fun press(
        owner: String,
        strings: List<String> = LONG_PRESS_STRINGS,
        sessionField: Boolean = true,
        registers: Int = 9,
    ): ClassDef {
        val fields = listOfNotNull(
            "activity" to mainActivity,
            if (sessionField) "session" to session else null,
            "flag" to "I",
        ).map { (name, type) -> ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null) }
        val longClick = method(owner, "onLongClick", listOf("Landroid/view/View;"), "Z", registers, AccessFlags.PUBLIC.value, """
            iget v0, p0, $owner->flag:I
            ${strings.joinToString("\n") { "const-string v1, \"$it\"" }}
            return v0
        """.trimIndent())
        return ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, listOf(longClick))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, flags: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}

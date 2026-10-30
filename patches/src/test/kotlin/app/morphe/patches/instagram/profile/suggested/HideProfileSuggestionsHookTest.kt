/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HideProfileSuggestionsHookTest {
    private val actions = "Lfixture/ProfileActions;"
    private val model = "Lfixture/ActionsModel;"
    private val header = "Lfixture/HeaderGroup;"
    private val row = "Lfixture/HeaderRow;"
    private val rowList = "Lfixture/Rows;"
    private val hooks = setOf(INLINE_ROW, CHAINING_BUTTON, KEEP_STANDALONE_ROW)

    /** The hooks the patch writes are in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in hooks) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The flag goes through the extension right after it's read and before it's tested, the button
     * right after it's set up, and the standalone row's read becomes the question, which keeps the
     * label a branch jumps to while the shared join further down stays on the type read.
     */
    @Test
    fun allThreeHooksGoInAndEveryJumpStillLands() {
        val context = PatchContexts.of(classes())

        context.hide()

        val bind = context.method(actions, "bind").code()
        val inline = bind.indexOfFirst { it.referenceText() == INLINE_ROW }
        assertEquals(Opcode.IGET_BOOLEAN, bind[inline - 1].opcode)
        assertEquals(listOf(0), bind[inline].arguments())
        assertEquals(Opcode.MOVE_RESULT, bind[inline + 1].opcode)
        assertEquals(0, (bind[inline + 1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, bind[inline + 2].opcode)
        val button = bind.indexOfFirst { it.referenceText() == CHAINING_BUTTON }
        assertEquals("setUp0", (bind[button - 1].reference() as MethodReference).name)
        assertEquals("the button is p1", listOf(5), bind[button].arguments())
        assertEquals(1, bind.count { it.referenceText() == INLINE_ROW })
        assertEquals(1, bind.count { it.referenceText() == CHAINING_BUTTON })

        val rows = context.method(header, BUILD_ROWS)
        assertStandaloneAsked("the stand-in", rows, register = 1)
        val code = rows.code()
        val keep = code.indexOfFirst { it.referenceText() == KEEP_STANDALONE_ROW }
        assertEquals("the branch to the standalone row lands on the question", keep, rows.targetOf(1))
        assertEquals("the bio row's jump to the join", keep + 4, rows.targetOf(4))
        assertEquals("the question skips to what came after the add", Opcode.RETURN_VOID, code[rows.targetOf(keep + 2)].opcode)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(actionRows = 0) to "profile action row holding \"$NO_SUGGESTED_BINDER\" in this Instagram build, found none",
            classes(actionRows = 2) to "profile action row holding \"$NO_SUGGESTED_BINDER\" in this Instagram build, found Lfixture/",
            classes(binderText = "$NO_SUGGESTED_BINDER yet") to "profile action row holding \"$NO_SUGGESTED_BINDER\" in this Instagram build, found none",
            classes(flags = 2) to "hides its suggested accounts when false, found 2",
            classes(flagOpcode = "iget") to "hides its suggested accounts when false, found 0",
            classes(hideValue = 4) to "hides its suggested accounts when false, found 0",
            classes(flagChecked = false) to "hides its suggested accounts when false, found 0",
            classes(jumpToFlagTest = true) to "jumps to the test of its suggestions' flag",
            classes(button = false) to "$FOLLOW_CHAINING_BUTTON isn't in this build",
            classes(buttonSuper = "Ljava/lang/Object;") to "$FOLLOW_CHAINING_BUTTON isn't an Android view",
            classes(setups = 0) to "setting up $FOLLOW_CHAINING_BUTTON, found 0",
            classes(setups = 2) to "setting up $FOLLOW_CHAINING_BUTTON, found 2",
            classes(setupInteger = "I") to "setting up $FOLLOW_CHAINING_BUTTON, found 0",
            classes(jumpAfterSetup = true) to "jumps to the instruction after $FOLLOW_CHAINING_BUTTON is set up",
            classes(headerBinds = 0) to "profile header binder holding \"$HEADER_BIND\" in this Instagram build, found none",
            classes(headerBinds = 2) to "profile header binder holding \"$HEADER_BIND\" in this Instagram build, found Lfixture/",
            classes(headerCreate = false) to "$header has no method holding \"$HEADER_CREATE\"",
            classes(rowSetups = 0) to "row type setup naming $STANDALONE_CHAINING in this Instagram build, found none",
            classes(rowSetups = 2) to "row type setup naming $STANDALONE_CHAINING in this Instagram build, found Lfixture/",
            classes(standaloneReads = 0) to "expected one read of $row->standalone:$row in $header->$BUILD_ROWS, found 0",
            classes(standaloneReads = 2) to "expected one read of $row->standalone:$row in $header->$BUILD_ROWS, found 2",
            classes(typeRead = "iget-boolean v0, v1, $row->shown:Z") to "doesn't read the row type's int right after",
            classes(typeRead = "iget v0, v1, Lfixture/OtherRow;->type:I") to "doesn't read the row type's int right after",
            classes(between = "const/4 v0, 0x1") to "doesn't add a row by the standalone row's type right after reading it",
            classes(readAfterAdd = true) to "reads v1 after adding the standalone row",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hide() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in hooks } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the action row, the button and the header's standalone row are found and hooked. */
    @Test
    fun eachDeclaredBuildGetsAllThreeHooks() {
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

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildGetsAllThreeHooksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = listOf(NO_SUGGESTED_BINDER, HEADER_BIND, STANDALONE_CHAINING)
            .flatMap { FixtureDex.classesHolding(bundle, it) }
        val buttonClass = FixtureDex.classes(bundle, setOf(FOLLOW_CHAINING_BUTTON)).values
        val context = PatchContexts.of((holders + buttonClass).distinctBy { it.type })

        context.hide()

        val written = holders.map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
        for (hook in hooks) {
            val calls = written.sumOf { method -> method.code().count { it.referenceText() == hook } }
            assertEquals("$label: calls to $hook", 1, calls)
        }
        val actionRow = written.single { method -> method.code().any { it.referenceText() == INLINE_ROW } }
        val code = actionRow.code()
        val inline = code.indexOfFirst { it.referenceText() == INLINE_ROW }
        val flag = code[inline - 1]
        assertEquals("$label: the flag read", Opcode.IGET_BOOLEAN, flag.opcode)
        val register = (flag as OneRegisterInstruction).registerA
        assertEquals("$label: the flag handed over", listOf(register), code[inline].arguments())
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[inline + 1].opcode)
        assertEquals("$label: the answer's register", register, (code[inline + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: the test", Opcode.IF_EQZ, code[inline + 2].opcode)
        assertTrue("$label: a jump skips the flag's hook", (inline..inline + 2).none { it in actionRow.jumpTargets() })

        val button = code.indexOfFirst { it.referenceText() == CHAINING_BUTTON }
        val setUp = code[button - 1].reference() as MethodReference
        assertEquals("$label: what comes before the button's hook", FOLLOW_CHAINING_BUTTON, setUp.definingClass)
        assertEquals("$label: the button handed over", code[button - 1].arguments().take(1), code[button].arguments())

        val rows = written.single { method -> method.code().any { it.referenceText() == KEEP_STANDALONE_ROW } }
        assertEquals("$label: the rows method", "buildRowViewTypes", rows.name)
        val keep = rows.code().indexOfFirst { it.referenceText() == KEEP_STANDALONE_ROW }
        assertStandaloneAsked(label, rows, (rows.code()[keep + 1] as OneRegisterInstruction).registerA)
    }

    /**
     * The question, its answer into the row's register, a skip on 0 to what came after the add, and
     * the read it replaced; then the type read and the add, untouched. Whatever jumped to the read of
     * the standalone row now lands on the question, and whatever jumped to the type read still does.
     */
    private fun assertStandaloneAsked(what: String, rows: MutableMethod, register: Int) {
        val code = rows.code()
        val asks = code.indices.filter { code[it].referenceText() == KEEP_STANDALONE_ROW }
        assertEquals("$what: asks", 1, asks.size)
        val keep = asks.single()
        assertEquals("$what: hands nothing over", emptyList<Int>(), code[keep].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[keep + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[keep + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the skip", Opcode.IF_EQZ, code[keep + 2].opcode)
        assertEquals("$what: the skip's register", register, (code[keep + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the skip lands after the add", keep + 6, rows.targetOf(keep + 2))
        assertEquals("$what: the read it replaced", Opcode.SGET_OBJECT, code[keep + 3].opcode)
        val field = code[keep + 3].reference() as FieldReference
        assertEquals("$what: the read's register", register, (code[keep + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: the type read", Opcode.IGET, code[keep + 4].opcode)
        assertEquals("$what: the type's class", field.type, (code[keep + 4].reference() as FieldReference).definingClass)
        val add = code[keep + 5].reference() as MethodReference
        assertEquals("$what: the add", listOf("I"), add.parameterTypes.map(CharSequence::toString))
        val targets = rows.jumpTargets()
        assertTrue("$what: nothing lands on the question", keep in targets)
        assertFalse("$what: a jump lands between the question and the read", (keep + 1..keep + 3).any { it in targets })
        assertTrue("$what: the join on the type read moved", keep + 4 in targets)
    }

    private fun BytecodePatchContext.hide() = hideProfileSuggestions(findProfileSuggestions())

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The action row's binder, which tests its model's show flag to show or hide the inline row and
     * sets the person-plus button up, and throws the binder message; the button; the header's
     * binder group, whose row list reads each row type's int and adds it, the bio row through a
     * shared join; and the row type enum, whose setup names the standalone row.
     */
    private fun classes(
        actionRows: Int = 1,
        binderText: String = NO_SUGGESTED_BINDER,
        flags: Int = 1,
        flagOpcode: String = "iget-boolean",
        hideValue: Int = 8,
        flagChecked: Boolean = true,
        jumpToFlagTest: Boolean = false,
        button: Boolean = true,
        buttonSuper: String = "Landroid/widget/FrameLayout;",
        setups: Int = 1,
        setupInteger: String = "Ljava/lang/Integer;",
        jumpAfterSetup: Boolean = false,
        headerBinds: Int = 1,
        headerCreate: Boolean = true,
        rowSetups: Int = 1,
        standaloneReads: Int = 1,
        typeRead: String = "iget v0, v1, $row->type:I",
        between: String = "",
        readAfterAdd: Boolean = false,
    ): List<ClassDef> {
        val flagType = if (flagOpcode == "iget-boolean") "Z" else "I"
        val flagReads = (0 until flags).joinToString("\n") { i ->
            """
            $flagOpcode v0, p0, $model->show$i:$flagType
            ${if (jumpToFlagTest && i == 0) ":test" else ""}
            if-eqz v0, :gone$i
            const/4 v1, 0x0
            goto :set$i
            :gone$i
            const/16 v1, 0x${Integer.toHexString(hideValue)}
            :set$i
            invoke-virtual { p2, v1 }, Landroid/view/View;->setVisibility(I)V
            ${if (jumpToFlagTest && i == 0) "if-nez v1, :test" else ""}
            """
        }
        val setUps = (0 until setups).joinToString("\n") { i ->
            "invoke-virtual { p1, v2, v2, v3 }, $FOLLOW_CHAINING_BUTTON->setUp$i(Lfixture/ButtonState;${setupInteger}Z)V"
        }
        val actionClasses = (0 until actionRows).map { copy ->
            val type = if (copy == 0) actions else "Lfixture/OtherActions;"
            val bind = method(
                type, "bind", listOf("Ljava/lang/Object;", FOLLOW_CHAINING_BUTTON, "Landroid/view/View;"), "V", 4, static = true,
                body = """
                    ${if (jumpAfterSetup) "if-eqz p1, :after" else ""}
                    instance-of v0, p0, ${if (flagChecked) model else "Lfixture/OtherModel;"}
                    if-eqz v0, :plain
                    check-cast p0, $model
                    $flagReads
                    :plain
                    const/4 v2, 0x0
                    const/4 v3, 0x1
                    $setUps
                    ${if (jumpAfterSetup) ":after" else ""}
                    const-string v0, "$binderText"
                    return-void
                """,
            )
            classDef(type, listOf(bind))
        }
        val modelClass = classDef(model, emptyList(), fields = (0 until flags).map { "show$it" to flagType })
        val buttonClass = if (button) listOf(classDef(FOLLOW_CHAINING_BUTTON, emptyList(), superclass = buttonSuper)) else emptyList()

        val rowEnums = (0 until rowSetups).map { copy ->
            val type = if (copy == 0) row else "Lfixture/OtherRow;"
            val setup = method(type, "<clinit>", emptyList(), "V", 2, static = true, body = """
                const-string v0, "ITEM_TYPE_BIO"
                sput-object v1, $type->bio:$type
                const-string v0, "$STANDALONE_CHAINING"
                sput-object v1, $type->standalone:$type
                return-void
            """)
            classDef(
                type, listOf(setup), superclass = "Ljava/lang/Enum;",
                fields = listOf("bio" to type, "standalone" to type, "type" to "I", "shown" to "Z"),
            )
        }
        val headerClasses = (0 until maxOf(headerBinds, 1)).map { copy ->
            val type = if (copy == 0) header else "Lfixture/OtherHeader;"
            val methods = mutableListOf<Method>()
            if (headerBinds > 0) {
                methods += method(type, "bindView", emptyList(), "V", 1, body = """
                    const-string v0, "$HEADER_BIND"
                    return-void
                """)
            }
            if (headerCreate) {
                methods += method(type, "createView", emptyList(), "V", 1, body = """
                    const-string v0, "$HEADER_CREATE"
                    return-void
                """)
            }
            if (copy == 0) {
                methods += method(type, BUILD_ROWS, listOf(rowList, "Ljava/lang/Object;", "Ljava/lang/Object;"), "V", 2, body = """
                    if-eqz p2, :bio
                    if-eqz p3, :standalone
                    goto :end
                    :bio
                    sget-object v1, $row->bio:$row
                    goto :join
                    :standalone
                    sget-object v1, $row->${if (standaloneReads == 0) "bio" else "standalone"}:$row
                    :join
                    $typeRead
                    $between
                    invoke-interface { p1, v0 }, $rowList->add(I)V
                    ${if (readAfterAdd) "invoke-interface { p1, v1 }, $rowList->keep(Ljava/lang/Object;)V" else ""}
                    :end
                    return-void
                """)
                if (standaloneReads > 1) {
                    methods += method(type, BUILD_ROWS, listOf(rowList), "V", 1, body = """
                        sget-object v0, $row->standalone:$row
                        return-void
                    """)
                }
            }
            classDef(type, methods)
        }
        return actionClasses + modelClass + buttonClass + rowEnums + headerClasses
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<clinit>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;", fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null,
            fields.map { (name, fieldType) ->
                val static = fieldType == type
                val access = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
                ImmutableField(type, name, fieldType, access, null, null, null)
            },
            methods,
        )

    private fun BytecodePatchContext.method(type: String, name: String): MutableMethod =
        mutableClassDefBy(type).methods.first { it.name == name }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}

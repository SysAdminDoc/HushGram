/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.highlights

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.profile.suggested.BUILD_ROWS
import app.morphe.patches.instagram.profile.suggested.FOLLOW_CHAINING_BUTTON
import app.morphe.patches.instagram.profile.suggested.HEADER_BIND
import app.morphe.patches.instagram.profile.suggested.HEADER_CREATE
import app.morphe.patches.instagram.profile.suggested.KEEP_STANDALONE_ROW
import app.morphe.patches.instagram.profile.suggested.NO_SUGGESTED_BINDER
import app.morphe.patches.instagram.profile.suggested.STANDALONE_CHAINING
import app.morphe.patches.instagram.profile.suggested.findProfileSuggestions
import app.morphe.patches.instagram.profile.suggested.hideProfileSuggestions
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

class HideHighlightsHookTest {
    private val header = "Lfixture/HeaderGroup;"
    private val row = "Lfixture/HeaderRow;"
    private val rowList = "Lfixture/Rows;"

    /** The hook the patch writes is in the ProfileHighlights the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(KEEP_TRAY.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$KEEP_TRAY is not in the extension: $declared", KEEP_TRAY.substringAfter("->") in declared)
    }

    /**
     * The tray's read becomes the question, which keeps the label a branch jumps to, and a 0 skips
     * to whatever the skip past the tray already landed on.
     */
    @Test
    fun theTrayIsAskedForAndEveryJumpStillLands() {
        val context = PatchContexts.of(classes())

        context.hide()

        val rows = context.mutableClassDefBy(header).methods.single { it.name == BUILD_ROWS }
        assertTrayAsked("the stand-in", rows, register = 1)
        val code = rows.code()
        val keep = code.indexOfFirst { it.referenceText() == KEEP_TRAY }
        assertEquals("the branch to the tray lands on the question", keep, rows.targetOf(0))
        assertEquals("the skip past the tray still lands after the add", keep + 6, rows.targetOf(1))
        assertEquals("what comes after the add", Opcode.CONST_4, code[keep + 6].opcode)
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(headerBinds = 0) to "profile header binder holding \"$HEADER_BIND\" in this Instagram build, found none",
            classes(headerBinds = 2) to "profile header binder holding \"$HEADER_BIND\" in this Instagram build, found Lfixture/",
            classes(headerCreate = false) to "$header has no method holding \"$HEADER_CREATE\"",
            classes(rowSetups = 0) to "row type setup naming $REEL_TRAY in this Instagram build, found none",
            classes(rowSetups = 2) to "row type setup naming $REEL_TRAY in this Instagram build, found Lfixture/",
            classes(trayNames = 2) to "$row's setup names $REEL_TRAY 2 times",
            classes(trayStored = false) to "$row's setup doesn't store $REEL_TRAY",
            classes(trayReads = 0) to "expected one read of $row->tray:$row in $header->$BUILD_ROWS, found 0",
            classes(trayReads = 2) to "expected one read of $row->tray:$row in $header->$BUILD_ROWS, found 2",
            classes(typeRead = "iget-boolean v0, v1, $row->shown:Z") to "doesn't read the row type's int right after",
            classes(typeRead = "iget v0, v1, Lfixture/OtherRow;->type:I") to "doesn't read the row type's int right after",
            classes(typeRead = "iget v0, v2, $row->type:I") to "doesn't read the row type's int right after",
            classes(between = "const/4 v0, 0x1") to "doesn't add a row by the highlights tray's type right after reading it",
            classes(add = "invoke-interface { p1, v0 }, $rowList->add(I)Z") to "doesn't add a row by the highlights tray's type",
            classes(readAfterAdd = true) to "reads v1 after adding the highlights tray",
            classes(readTypeAfterAdd = true) to "reads v0, the row type's int, after adding the highlights tray",
            classes(setup = nameToNoConstructor) to "$row's setup doesn't build a row type from $REEL_TRAY",
            classes(setup = builtOnAStoredObject) to "$row's setup doesn't make the $REEL_TRAY row type with new-instance",
            classes(setup = siblingInTheTraysField) to "$row's setup doesn't store $REEL_TRAY",
            classes(setup = objectWrittenOverBeforeItsStore) to "$row's setup doesn't store $REEL_TRAY",
            classes(setup = jumpIntoTheStore) to "$row's setup doesn't store $REEL_TRAY",
            classes(jumpIntoTray = 1) to "has a jump into the highlights tray's type read or add",
            classes(jumpIntoTray = 2) to "has a jump into the highlights tray's type read or add",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hide() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    val after = now.getValue(method.key())
                    assertEquals("$expected: ${original.type}->${method.name} changed", method.code().map { it.describe() }, after.code().map { it.describe() })
                }
            }
        }
    }

    /**
     * A setup that makes the tray first and stores a sibling before it still gives the tray's own
     * field, followed through the registers, so the sibling's row, read and added the same way
     * earlier in the list, is left alone.
     */
    @Test
    fun aReorderedSetupStillFindsTheTraysOwnField() {
        val context = PatchContexts.of(classes(setup = siblingStoredFirst, bioRow = true))

        context.hide()

        val rows = context.mutableClassDefBy(header).methods.single { it.name == BUILD_ROWS }
        assertTrayAsked("the reordered stand-in", rows, register = 1)
        val code = rows.code()
        val keep = code.indexOfFirst { it.referenceText() == KEEP_TRAY }
        assertEquals("the hidden row", "$row->tray:$row", code[keep + 3].referenceText())
        assertEquals("the sibling's row", "$row->bio:$row", code[0].referenceText())
        assertEquals("the sibling's add", "$rowList->add(I)V", code[2].referenceText())
    }

    /** In each declared build the header's tray row is found and asked for, once. */
    @Test
    fun eachDeclaredBuildAsksForTheTray() {
        forEachFixture { builds(it, it.name) }
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksForTheTrayToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = listOf(HEADER_BIND, REEL_TRAY).flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
        val context = PatchContexts.of(holders)
        val site = context.findHighlightsRow()
        val stockRows = context.mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        val original = NeutralNativePath(stockRows)

        context.hide()

        val written = holders.flatMap { context.mutableClassDefBy(it.type).methods }
            .filter { method -> method.code().any { it.referenceText() == KEEP_TRAY } }
        assertEquals("$label: methods asking", 1, written.size)
        val rows = written.single()
        assertEquals("$label: the rows method", site.name, rows.name)
        val keep = rows.code().indexOfFirst { it.referenceText() == KEEP_TRAY }
        assertTrayAsked(label, rows, (rows.code()[keep + 1] as OneRegisterInstruction).registerA)
        original.assertPreserved(label, rows, (keep..keep + 2).toSet())
    }

    /**
     * Hide suggested people on profiles hooks the same list of rows. Picked together, in either
     * order, each patch finds its own row and both questions are in, once each.
     */
    @Test
    fun eachDeclaredBuildTakesBothProfilePatchesInEitherOrder() {
        forEachFixture { bothPatchesBuilds(it, it.name) }
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildTakesBothProfilePatchesInEitherOrderToo() {
        for (base in Fixtures.otherBuilds()) bothPatchesBuilds(base, base.parentFile.name)
    }

    private fun bothPatchesBuilds(bundle: File, label: String) {
        val holders = listOf(NO_SUGGESTED_BINDER, HEADER_BIND, STANDALONE_CHAINING, REEL_TRAY)
            .flatMap { FixtureDex.classesHolding(bundle, it) }
        val button = FixtureDex.classes(bundle, setOf(FOLLOW_CHAINING_BUTTON)).values
        val classes = (holders + button).distinctBy { it.type }
        val highlights: BytecodePatchContext.() -> Unit = { hide() }
        val suggestions: BytecodePatchContext.() -> Unit = { hideProfileSuggestions(findProfileSuggestions()) }
        for ((order, steps) in listOf(listOf(highlights, suggestions), listOf(suggestions, highlights)).withIndex()) {
            val context = PatchContexts.of(classes)
            steps.forEach { step -> step(context) }

            val rows = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                .single { method -> method.code().any { it.referenceText() == KEEP_TRAY } }
            assertEquals("$label order $order: the standalone row's question", 1, rows.code().count { it.referenceText() == KEEP_STANDALONE_ROW })
            val keep = rows.code().indexOfFirst { it.referenceText() == KEEP_TRAY }
            assertTrayAsked("$label order $order", rows, (rows.code()[keep + 1] as OneRegisterInstruction).registerA)
        }
    }

    private fun forEachFixture(check: (java.io.File) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The question, its answer into the tray's register, a skip on 0 to what came after the add,
     * and the read it replaced; then the type read and the add, untouched. Nothing jumps in between.
     */
    private fun assertTrayAsked(what: String, rows: MutableMethod, register: Int) {
        val code = rows.code()
        val asks = code.indices.filter { code[it].referenceText() == KEEP_TRAY }
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
        assertFalse("$what: a jump lands between the question and the add", (keep + 1..keep + 5).any { it in rows.jumpTargets() })
    }

    private fun BytecodePatchContext.hide() = hideHighlights(findHighlightsRow())

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    private val rowInit = "$row-><init>(Ljava/lang/String;I)V"

    /** The tray is made first, javac's way with the object before its name, and the bio stored before it. */
    private val siblingStoredFirst = """
        new-instance v3, $row
        const-string v0, "$REEL_TRAY"
        const/4 v1, 0x1
        invoke-direct { v3, v0, v1 }, $rowInit
        const-string v0, "ITEM_TYPE_BIO"
        const/4 v1, 0x0
        new-instance v2, $row
        invoke-direct { v2, v0, v1 }, $rowInit
        sput-object v2, $row->bio:$row
        sput-object v3, $row->tray:$row
        return-void
    """

    /** The name is loaded, but the constructor after it gets another one. */
    private val nameToNoConstructor = """
        const-string v0, "$REEL_TRAY"
        const-string v2, "ITEM_TYPE_BIO"
        const/4 v1, 0x1
        new-instance v3, $row
        invoke-direct { v3, v2, v1 }, $rowInit
        sput-object v3, $row->tray:$row
        const/4 v0, 0x0
        return-void
    """

    /** The constructor that takes the name runs on an object read from a field, not a new one. */
    private val builtOnAStoredObject = """
        const-string v0, "$REEL_TRAY"
        const/4 v1, 0x1
        sget-object v3, $row->bio:$row
        invoke-direct { v3, v0, v1 }, $rowInit
        sput-object v3, $row->tray:$row
        return-void
    """

    /** The tray's field holds the bio's object, and the tray's own object is never stored. */
    private val siblingInTheTraysField = """
        const-string v0, "ITEM_TYPE_BIO"
        const/4 v1, 0x0
        new-instance v2, $row
        invoke-direct { v2, v0, v1 }, $rowInit
        const-string v0, "$REEL_TRAY"
        const/4 v1, 0x1
        new-instance v3, $row
        invoke-direct { v3, v0, v1 }, $rowInit
        sput-object v2, $row->tray:$row
        return-void
    """

    /** The register holding the tray's object is written over before the store. */
    private val objectWrittenOverBeforeItsStore = """
        const-string v0, "$REEL_TRAY"
        const/4 v1, 0x1
        new-instance v3, $row
        invoke-direct { v3, v0, v1 }, $rowInit
        sget-object v3, $row->bio:$row
        sput-object v3, $row->tray:$row
        return-void
    """

    /** A jump lands on the store, so on that path the register never held the tray's object. */
    private val jumpIntoTheStore = """
        const/4 v1, 0x1
        if-eqz v1, :store
        const-string v0, "$REEL_TRAY"
        new-instance v3, $row
        invoke-direct { v3, v0, v1 }, $rowInit
        :store
        sput-object v3, $row->tray:$row
        return-void
    """

    /**
     * The header's binder group, whose row list skips the tray when its check says so, and
     * otherwise reads the tray's row type, its int and adds it; and the row type enum, whose setup
     * names the tray.
     */
    private fun classes(
        headerBinds: Int = 1,
        headerCreate: Boolean = true,
        rowSetups: Int = 1,
        trayNames: Int = 1,
        trayStored: Boolean = true,
        trayReads: Int = 1,
        typeRead: String = "iget v0, v1, $row->type:I",
        between: String = "",
        add: String = "invoke-interface { p1, v0 }, $rowList->add(I)V",
        readAfterAdd: Boolean = false,
        readTypeAfterAdd: Boolean = false,
        setup: String? = null,
        bioRow: Boolean = false,
        jumpIntoTray: Int = 0,
    ): List<ClassDef> {
        val rowEnums = (0 until rowSetups).map { copy ->
            val type = if (copy == 0) row else "Lfixture/OtherRow;"
            val names = (0 until trayNames).joinToString("\n") { "const-string v0, \"$REEL_TRAY\"" }
            val store = if (trayStored) "sput-object v3, $type->tray:$type" else "sput-object v3, $type->name:Ljava/lang/Object;"
            // Shaped like Instagram 449's: each name goes to a constructor run on a copy of a new
            // object, and the object itself is stored.
            val body = setup.takeIf { copy == 0 } ?: """
                const-string v0, "ITEM_TYPE_BIO"
                const/4 v1, 0x0
                new-instance v2, $type
                invoke-direct { v2, v0, v1 }, $type-><init>(Ljava/lang/String;I)V
                sput-object v2, $type->bio:$type
                $names
                const/4 v1, 0x1
                new-instance v3, $type
                move-object v4, v3
                invoke-direct { v4, v0, v1 }, $type-><init>(Ljava/lang/String;I)V
                $store
                return-void
            """
            val setupMethod = method(type, "<clinit>", emptyList(), "V", 5, static = true, body = body)
            classDef(
                type, listOf(setupMethod), superclass = "Ljava/lang/Enum;",
                fields = listOf("bio" to type, "tray" to type, "type" to "I", "shown" to "Z", "name" to "Ljava/lang/Object;"),
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
                val bio = if (!bioRow) "" else """
                    sget-object v1, $row->bio:$row
                    iget v0, v1, $row->type:I
                    invoke-interface { p1, v0 }, $rowList->add(I)V
                """.trimIndent()
                methods += method(type, BUILD_ROWS, listOf(rowList, "Ljava/lang/Object;", "Ljava/lang/Object;"), "V", 3, body = """
                    $bio
                    if-eqz p3, :${when (jumpIntoTray) { 1 -> "trayType"; 2 -> "trayAdd"; else -> "tray" }}
                    if-nez p2, :past
                    :tray
                    sget-object v1, $row->${if (trayReads == 0) "bio" else "tray"}:$row
                    :trayType
                    $typeRead
                    $between
                    :trayAdd
                    $add
                    ${if (readAfterAdd) "invoke-interface { p1, v1 }, $rowList->keep(Ljava/lang/Object;)V" else ""}
                    ${if (readTypeAfterAdd) "invoke-interface { p1, v0 }, $rowList->keepType(I)V" else ""}
                    :past
                    const/4 v1, 0x0
                    return-void
                """)
                if (trayReads > 1) {
                    methods += method(type, BUILD_ROWS, listOf(rowList), "V", 1, body = """
                        sget-object v0, $row->tray:$row
                        return-void
                    """)
                }
            }
            classDef(type, methods)
        }
        return rowEnums + headerClasses
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
                val static = fieldType == type || name == "name"
                val access = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
                ImmutableField(type, name, fieldType, access, null, null, null)
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

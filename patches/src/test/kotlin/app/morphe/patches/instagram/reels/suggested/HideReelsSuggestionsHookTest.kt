/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.suggested

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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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

class HideReelsSuggestionsHookTest {
    private val converter = "Lfixture/Converter;"
    private val item = "Lfixture/ClipsItem;"
    private val kind = "Lfixture/ClipsKind;"
    private val unit = "Lfixture/NetegoUnit;"
    private val binders = "Lfixture/Binders;"
    private val items = "Lfixture/Items;"
    private val pages = "Lfixture/Pages;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val hooks = setOf(REELS_FILTER, NETEGO_FILTER, FRIENDS_TAB_FILTER)

    /** The hooks the patch writes are in the ReelsSuggestions the bundle ships, public and static. */
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
     * Every return hands its item to the filter first, a branch straight to a return included, and the
     * netego return hands its item and the unit's type to the netego hook before that.
     */
    @Test
    fun everyReturnIsFilteredAndTheNetegoUnitFirst() {
        val context = PatchContexts.of(classes())

        context.hide()

        val method = context.mutableClassDefBy(converter).methods.single { it.name == "convert" }
        assertEveryReturnFiltered("the stand-in", method, item)
        val code = method.code()
        val none = code.indices.last { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("the branch to the last return lands on its filter", none - 3, method.targetOf(2))
        assertNetegoAsked("the stand-in", method, unitRegister = 0, itemRegister = 1)
        assertTrue("the converter has no friends tab filter", code.none { it.referenceText() == FRIENDS_TAB_FILTER })
    }

    /**
     * The friends tab's page of people to follow (#167) passes through its own filter at every return
     * of the method making it, a branch straight to the null return included, and through nothing else.
     */
    @Test
    fun everyReturnOfTheFriendsTabPageIsFiltered() {
        val context = PatchContexts.of(classes())

        context.hide()

        val page = context.mutableClassDefBy(pages).methods.single { it.name == "friendsTab" }
        assertEveryReturnFiltered("the stand-in page", page, item, FRIENDS_TAB_FILTER)
        val code = page.code()
        val none = code.indices.last { code[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("the branch to the null return lands on its filter", none - 3, page.targetOf(2))
        assertTrue("the page has only its own filter", code.none { it.referenceText() == REELS_FILTER || it.referenceText() == NETEGO_FILTER })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val oneType = "expected the Reels binder dispatch to read one netego unit type, found"
        val cases = listOf(
            classes(converters = 0) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found none",
            classes(converters = 2) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found Lfixture/",
            classes(marker = "${TO_CLIPS_ITEM}_v2") to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found none",
            classes(converterAnswers = "Ljava/lang/Object;") to "answers Ljava/lang/Object;, which isn't a class of the app",
            classes(kindNames = SUGGESTED_KINDS.drop(1)) to "expected one enum field of $item whose type names all of $SUGGESTED_KINDS",
            classes(kindField = "Ljava/lang/String;") to "expected one enum field of $item whose type names all of $SUGGESTED_KINDS",
            classes(dispatches = 0) to "comparing $CREATORS_TO_FOLLOW in this Instagram build, found none",
            classes(dispatches = 2) to "comparing $CREATORS_TO_FOLLOW in this Instagram build, found Lfixture/",
            classes(creators = "${CREATORS_TO_FOLLOW}_v2") to "comparing $CREATORS_TO_FOLLOW in this Instagram build, found none",
            classes(dispatchTakes = "Lfixture/OtherItem;") to "doesn't take a $item",
            classes(unitTypeReads = listOf("iget v1, v0, $unit->size:I")) to "$oneType []",
            classes(unitTypeReads = listOf("iget-object v1, v0, $unit->type:Ljava/lang/String;", "iget-object v1, v0, $unit->subtype:Ljava/lang/String;")) to
                "$oneType [$unit->type:Ljava/lang/String;, $unit->subtype:Ljava/lang/String;]",
            classes(madeFrom = "Lfixture/OtherUnit;") to "expected $converter->convert to return one item made from a $unit, found 0",
            classes(netegoReturns = 2) to "expected $converter->convert to return one item made from a $unit, found 2",
            classes(madeInto = 0) to "writes the netego item over its unit before returning it",
            classes(madeByInstance = true) to "makes its netego item with invoke-virtual, not a static call",
            classes(jumpToNetegoReturn = true) to "something in $converter->convert jumps to the return of its netego item",
            classes(locals = 2) to "needs 2",
            classes(pageMakers = 0) to "Reels friends tab page maker holding \"$TO_FRIENDS_TAB_PAGE\" in this Instagram build, found none",
            classes(pageMakers = 2) to "Reels friends tab page maker holding \"$TO_FRIENDS_TAB_PAGE\" in this Instagram build, found Lfixture/",
            classes(pageMarker = "${TO_FRIENDS_TAB_PAGE}_v2") to
                "Reels friends tab page maker holding \"$TO_FRIENDS_TAB_PAGE\" in this Instagram build, found none",
            classes(pageAnswers = "Lfixture/OtherItem;") to "$pages->friendsTab answers Lfixture/OtherItem;, not a $item",
            classes(pageReturns = false) to "$pages->friendsTab returns no item",
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

    /** In each declared build every return of the converter is filtered, and its netego return asks first. */
    @Test
    fun eachDeclaredBuildFiltersEveryReturn() {
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
    fun eachOtherBuildFiltersEveryReturnToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val converters = FixtureDex.classesHolding(bundle, TO_CLIPS_ITEM)
        val pageMakers = FixtureDex.classesHolding(bundle, TO_FRIENDS_TAB_PAGE)
        val dispatches = FixtureDex.classesHolding(bundle, CREATORS_TO_FOLLOW)
        val itemType = converters.flatMap { it.methods }.single { method -> method.code().any { it.string() == TO_CLIPS_ITEM } }.returnType
        val itemClass = FixtureDex.classes(bundle, setOf(itemType)).values
        val fieldTypes = itemClass.flatMap { it.fields }.map { it.type }.filter { it.startsWith("L") }.toSet()
        val kinds = FixtureDex.classes(bundle, fieldTypes).values
        val context = PatchContexts.of((converters + pageMakers + dispatches + itemClass + kinds).distinctBy { it.type })

        context.hide()

        val methods = (converters + pageMakers).map { it.type }.distinct().flatMap { context.mutableClassDefBy(it).methods }
        val page = methods.filter { method -> method.code().any { it.referenceText() == FRIENDS_TAB_FILTER } }
        assertEquals("${label}: friends tab page makers hooked", 1, page.size)
        assertTrue("${label}: the friends tab page maker", page.single().code().any { it.string() == TO_FRIENDS_TAB_PAGE })
        assertEveryReturnFiltered("$label friends tab page", page.single(), itemType, FRIENDS_TAB_FILTER)
        // The DexDiff rule for this hook counts its sites, one per return.
        assertEquals("${label}: friends tab page returns", 2, page.single().code().count { it.opcode == Opcode.RETURN_OBJECT })
        val written = methods.filter { method -> method.code().any { it.referenceText() == REELS_FILTER || it.referenceText() == NETEGO_FILTER } }
        assertEquals("${label}: methods hooked", 1, written.size)
        val method = written.single()
        assertTrue("${label}: the converter has no friends tab filter", method.code().none { it.referenceText() == FRIENDS_TAB_FILTER })
        assertTrue("${label}: the converter", method.code().any { it.string() == TO_CLIPS_ITEM })
        assertEveryReturnFiltered(label, method, itemType)
        val code = method.code()
        val asked = code.indexOfFirst { it.referenceText() == NETEGO_FILTER }
        val made = code[asked - 4]
        assertEquals("${label}: what the netego hook follows", Opcode.MOVE_RESULT_OBJECT, made.opcode)
        val call = code[asked - 5].reference() as MethodReference
        assertEquals("${label}: the call that made the netego item", itemType, call.returnType)
        assertNetegoAsked(
            label, method,
            unitRegister = code[asked - 5].arguments().first(), itemRegister = (made as OneRegisterInstruction).registerA,
        )
    }

    /**
     * Each return is the end of the filter, its answer and a cast back to the item, all in the
     * return's register; the filter is called once per return; nothing lands between the filter
     * and its return.
     */
    private fun assertEveryReturnFiltered(what: String, method: MutableMethod, itemType: String, filter: String = REELS_FILTER) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue("$what: no returns", returns.isNotEmpty())
        assertEquals("$what: filter calls", returns.size, code.count { it.referenceText() == filter })
        val targets = method.jumpTargets()
        for (at in returns) {
            val register = (code[at] as OneRegisterInstruction).registerA
            assertEquals("$what: the filter before return $at", filter, code[at - 3].referenceText())
            assertEquals("$what: the filter's register at $at", listOf(register), code[at - 3].arguments())
            assertEquals("$what: the answer at $at", Opcode.MOVE_RESULT_OBJECT, code[at - 2].opcode)
            assertEquals("$what: the answer's register at $at", register, (code[at - 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: the cast at $at", Opcode.CHECK_CAST, code[at - 1].opcode)
            assertEquals("$what: the cast's type at $at", itemType, (code[at - 1].reference() as TypeReference).type)
            assertTrue("$what: a jump skips the filter of return $at", (at - 2..at).none { it in targets })
        }
    }

    /**
     * Before the netego return's filter, the unit's type is read into a borrowed register, the item
     * moved into another, both handed to the netego hook, and its answer cast back into the item's
     * own register.
     */
    private fun assertNetegoAsked(what: String, method: MutableMethod, unitRegister: Int, itemRegister: Int) {
        val code = method.code()
        val asks = code.indices.filter { code[it].referenceText() == NETEGO_FILTER }
        assertEquals("$what: netego asks", 1, asks.size)
        val ask = asks.single()
        val (borrowedItem, borrowedType) = code[ask].arguments()
        assertTrue("$what: the borrowed registers fit an invoke", borrowedItem <= 15 && borrowedType <= 15)
        assertNotEquals("$what: the type was read into the item's register", itemRegister, borrowedType)
        assertEquals("$what: the unit moved", Opcode.MOVE_OBJECT_FROM16, code[ask - 3].opcode)
        assertEquals("$what: the unit moved", listOf(borrowedType, unitRegister), code[ask - 3].twoRegisters())
        assertEquals("$what: the type read", Opcode.IGET_OBJECT, code[ask - 2].opcode)
        assertEquals("$what: the type read", listOf(borrowedType, borrowedType), code[ask - 2].twoRegisters())
        assertEquals("$what: the type's field", "Ljava/lang/String;", (code[ask - 2].reference() as FieldReference).type)
        assertEquals("$what: the item moved", Opcode.MOVE_OBJECT_FROM16, code[ask - 1].opcode)
        assertEquals("$what: the item moved", listOf(borrowedItem, itemRegister), code[ask - 1].twoRegisters())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[ask + 1].opcode)
        assertEquals("$what: the answer's register", itemRegister, (code[ask + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[ask + 2].opcode)
        assertEquals("$what: then the filter", REELS_FILTER, code[ask + 3].referenceText())
        assertEquals("$what: then the return", Opcode.RETURN_OBJECT, code[ask + 6].opcode)
        assertEquals("$what: the return's register", itemRegister, (code[ask + 6] as OneRegisterInstruction).registerA)
        val targets = method.jumpTargets()
        assertTrue("$what: a jump lands inside the netego hook", (ask - 3..ask + 6).none { it in targets })
    }

    private fun BytecodePatchContext.hide() = hideReelsSuggestions(findReelsSuggestions())

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The converter, which returns a new item, the item a call makes from a netego unit, and null
     * through a branch straight to its return; the clips item with its kind enum; the Reels binder
     * dispatch, which reads the netego unit's type and compares it with both types; and the friends
     * tab's page maker, which returns a new item, or null through a branch straight to its return.
     */
    private fun classes(
        converters: Int = 1,
        marker: String = TO_CLIPS_ITEM,
        converterAnswers: String = item,
        kindNames: List<String> = SUGGESTED_KINDS,
        kindField: String = kind,
        dispatches: Int = 1,
        creators: String = CREATORS_TO_FOLLOW,
        dispatchTakes: String = item,
        unitTypeReads: List<String> = listOf("iget-object v1, v0, $unit->type:Ljava/lang/String;"),
        madeFrom: String = unit,
        netegoReturns: Int = 1,
        madeInto: Int = 1,
        jumpToNetegoReturn: Boolean = false,
        madeByInstance: Boolean = false,
        locals: Int = 4,
        pageMakers: Int = 1,
        pageMarker: String = TO_FRIENDS_TAB_PAGE,
        pageAnswers: String = item,
        pageReturns: Boolean = true,
    ): List<ClassDef> {
        // An instance call's first register is its receiver, so the unit isn't where a static call keeps it.
        val fromUnit = if (madeByInstance) {
            "invoke-virtual { v0, v0, p1 }, $items->fromUnit($madeFrom$session)$item"
        } else {
            "invoke-static { v0, p1 }, $items->fromUnit($madeFrom$session)$item"
        }
        val firstItem = if (netegoReturns > 1) {
            "new-instance v0, $unit\n$fromUnit\nmove-result-object v1"
        } else {
            "new-instance v1, $item\ninvoke-direct { v1 }, $item-><init>()V"
        }
        val converterClasses = (0 until converters).map { copy ->
            val type = if (copy == 0) converter else "Lfixture/OtherConverter;"
            val convert = method(type, "convert", listOf("Lfixture/ServerItem;", session), converterAnswers, locals, static = true, body = """
                const-string v0, "$marker"
                const/4 v1, 0x0
                if-eqz p0, :none
                ${if (jumpToNetegoReturn) "if-eqz v1, :back" else ""}
                if-eqz p1, :netego
                $firstItem
                return-object v1
                :netego
                new-instance v0, $unit
                invoke-direct { v0 }, $unit-><init>()V
                $fromUnit
                move-result-object v$madeInto
                ${if (jumpToNetegoReturn) ":back" else ""}
                return-object v$madeInto
                :none
                return-object v1
            """)
            classDef(type, listOf(convert))
        }
        val kindEnum = classDef(
            kind,
            listOf(method(kind, "<clinit>", emptyList(), "V", 1, static = true, body = (listOf("MEDIA", "NETEGO") + kindNames)
                .joinToString("\n", postfix = "\nreturn-void") { "const-string v0, \"$it\"" })),
            superclass = "Ljava/lang/Enum;",
        )
        val itemClass = classDef(item, emptyList(), fields = listOf("kind" to kindField, "unit" to unit, "id" to "Ljava/lang/String;"))
        val unitClass = classDef(unit, emptyList(), fields = listOf("type" to "Ljava/lang/String;", "subtype" to "Ljava/lang/String;", "size" to "I"))
        val dispatchClasses = (0 until dispatches).map { copy ->
            val type = if (copy == 0) binders else "Lfixture/OtherBinders;"
            val bind = method(type, "bind", listOf(dispatchTakes, "I"), "Lfixture/Binder;", 3, body = """
                iget-object v0, p1, $item->unit:$unit
                ${unitTypeReads.joinToString("\n")}
                const-string v2, "$creators"
                iget-object v1, p0, $type->name:Ljava/lang/String;
                iget-object v1, p1, $item->id:Ljava/lang/String;
                const/4 v0, 0x0
                return-object v0
            """)
            classDef(type, listOf(bind), fields = listOf("name" to "Ljava/lang/String;"))
        }
        val made = if (pageReturns) {
            """
                const/4 v1, 0x0
                if-eqz p0, :none
                new-instance v1, $item
                invoke-direct { v1 }, $item-><init>()V
                return-object v1
                :none
                return-object v1
            """
        } else {
            """
                new-instance v1, Ljava/lang/IllegalStateException;
                invoke-direct { v1 }, Ljava/lang/IllegalStateException;-><init>()V
                throw v1
            """
        }
        val pageClasses = (0 until pageMakers).map { copy ->
            val type = if (copy == 0) pages else "Lfixture/OtherPages;"
            val friendsTab = method(type, "friendsTab", listOf("Lfixture/ServerPage;"), pageAnswers, 2, static = true,
                body = "const-string v0, \"$pageMarker\"\n" + made.trimIndent())
            classDef(type, listOf(friendsTab))
        }
        return converterClasses + kindEnum + itemClass + unitClass + dispatchClasses + pageClasses
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

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;", fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
            methods,
        )

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string

    private fun Instruction.twoRegisters(): List<Int> = (this as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) }

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}

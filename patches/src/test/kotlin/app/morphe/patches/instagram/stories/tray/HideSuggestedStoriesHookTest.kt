/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideSuggestedStoriesHookTest {
    /** Both hooks the patch writes are in the StoriesTray the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(HIDE_TRAY, TRAY_FILTER, TRAY_REMAINING_FILTER)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** The row build asks the extension first, and on a yes returns before adding its row. */
    @Test
    fun theTrayRowIsGuarded() {
        val context = PatchContexts.of(listOf(trayRows()))

        context.guardTrayRow(context.findTrayRowBuild())

        assertGuardedFirst("stand-in", context.mutableClassDefBy(ROWS).methods.single { it.name == "buildRowViewTypes" })
    }

    @Test
    fun twoRowBuildsFailThePatch() {
        val context = PatchContexts.of(listOf(trayRows(), trayRows(type = "Lfixture/OtherTrayRows;")))
        assertThrows(PatchException::class.java) { context.findTrayRowBuild() }
    }

    /** A row build that no longer adds a row through its builder is an update the patch hasn't seen. */
    @Test
    fun aRowBuildAddingNoRowFailsThePatch() {
        val context = PatchContexts.of(listOf(trayRows(adds = false)))
        assertThrows(PatchException::class.java) { context.findTrayRowBuild() }
    }

    /** Each tray item goes past the extension right after it's read, ahead of the parser's null test. */
    @Test
    fun eachTrayItemIsFiltered() {
        val context = PatchContexts.of(trayClasses())

        context.filterTrayItems(context.findTrayItemParse())

        assertFilteredBeforeTheTest("stand-in", context.mutableClassDefBy(TRAY).methods.single { it.name == "unsafeParseFromJson" })
    }

    /** The ids of the reels the tray fetches after its items go past the extension right after they're read. */
    @Test
    fun theReelsLeftToFetchGoPastTheExtension() {
        val context = PatchContexts.of(trayClasses())
        val parse = context.findTrayItemParse()

        context.hookTrayParser(parse, context.findTrayRemaining(parse.site))

        val method = context.mutableClassDefBy(TRAY).methods.single { it.name == "unsafeParseFromJson" }
        assertTrimmedAfterTheRead("stand-in", method)
        assertFilteredBeforeTheTest("stand-in", method)
    }

    @Test
    fun aTrayParserNotReadingTheReelsLeftFailsThePatch() {
        val context = PatchContexts.of(trayClasses(remaining = false))
        val site = context.findTrayItemParse().site
        assertThrows(PatchException::class.java) { context.findTrayRemaining(site) }
    }

    @Test
    fun aTrayParserWithoutTheNullTestFailsThePatch() {
        val context = PatchContexts.of(trayClasses(tested = false))
        assertThrows(PatchException::class.java) { context.findTrayItemParse() }
    }

    /** Items of another kind of reel have no reel type naming the suggested ones, which the extension couldn't read. */
    @Test
    fun anItemWithoutTheSuggestedReelTypesFailsThePatch() {
        val context = PatchContexts.of(trayClasses(reelTypes = listOf("USER_REEL", "HIGHLIGHT_REEL")))
        assertThrows(PatchException::class.java) { context.findTrayItemParse() }
    }

    /** A reel type without a rewind or a recap kind a switch takes out fails too: that switch would do nothing. */
    @Test
    fun anItemWithoutARewindOrRecapTypeFailsThePatch() {
        for (gone in MADE_REELS) {
            val context = PatchContexts.of(trayClasses(reelTypes = listOf("USER_REEL") + SUGGESTED_REELS + (MADE_REELS - gone)))
            assertThrows(gone, PatchException::class.java) { context.findTrayItemParse() }
        }
    }

    @Test
    fun anItemNotATrayItemFailsThePatch() {
        val context = PatchContexts.of(trayClasses(itemInterface = "Lfixture/SomethingElse;"))
        assertThrows(PatchException::class.java) { context.findTrayItemParse() }
    }

    @Test
    fun theExtensionIsLeftAlone() {
        val own = trayRows(type = "Lapp/hushgram/extension/instagram/stories/Probe;")
        val context = PatchContexts.of(listOf(own, trayRows()))

        assertEquals(ROWS, context.findTrayRowBuild().type)
    }

    /**
     * In each declared build the tray's row build and its item read are found, and after the patch
     * both go through the extension. On 449 that's LX/01gX and LX/03vx.
     */
    @Test
    fun eachDeclaredBuildGuardsTheTrayAndFiltersItsItems() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        val keep = classDef.superclass == "Ljava/lang/Enum;" || TRAY_ITEM_INTF in classDef.interfaces ||
                            classDef.methods.any { it.name == "parseFromJsonParser" || it.holds(TRAY_ROWS) || it.holds(TRAY_REMAINING) }
                        if (keep) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val rows = context.findTrayRowBuild()
                val parse = context.findTrayItemParse()
                val remaining = context.findTrayRemaining(parse.site)
                context.guardTrayRow(rows)
                context.hookTrayParser(parse, remaining)

                assertGuardedFirst(
                    "${bundle.name} ${rows.type}",
                    context.mutableClassDefBy(rows.type).methods.single { it.name == rows.name && it.parameterTypes.map(CharSequence::toString) == rows.parameters },
                )
                val parser = context.mutableClassDefBy(parse.site.type).methods.single { it.name == "unsafeParseFromJson" && it.parameterTypes.size == 1 }
                assertFilteredBeforeTheTest("${bundle.name} ${parse.site.type}", parser)
                assertTrimmedAfterTheRead("${bundle.name} ${parse.site.type}", parser)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    /** The method opens with the extension call, its answer, a test of it and a return-void; one call in all. */
    private fun assertGuardedFirst(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_TRAY })
        assertEquals("$what: the call", HIDE_TRAY, (code[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the tested register", (code[1] as OneRegisterInstruction).registerA, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[3].opcode)
    }

    /** The item's read is followed by the extension call on its register, the answer back in it, and the null test; one call in all. */
    private fun assertFilteredBeforeTheTest(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == TRAY_FILTER }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: the read", "parseFromJsonParser", (code[hook - 2] as ReferenceInstruction).reference.toString().substringAfter("->").substringBefore("("))
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the read's result", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the test", Opcode.IF_EQZ, code[hook + 2].opcode)
        assertEquals("$what: the tested register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
    }

    /** The reel id read is followed by the extension call on its register and the answer back in it; one call in all. */
    private fun assertTrimmedAfterTheRead(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == TRAY_REMAINING_FILTER }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: the read", "Ljava/util/ArrayList;", (code[hook - 2] as ReferenceInstruction).reference.toString().substringAfterLast(")"))
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the read's result", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
    }

    private companion object {
        const val ROWS = "Lfixture/StoryTrayRows;"
        const val BUILDER = "Lfixture/RowBuilder;"
        const val TRAY = "Lfixture/TrayResponseParser;"
        const val PARSER = "Lfixture/ReelItemParser;"
        const val BASE = "Lfixture/JsonParserBase;"
        const val ITEM = "Lfixture/ReelItem;"
        const val REEL_TYPE = "Lfixture/ReelType;"
        const val JSON = "Lfixture/Json;"

        val INSTANCE = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

        fun string(register: Int, value: String) = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

        fun classOf(type: String, methods: List<ImmutableMethod>, superclass: String = "Ljava/lang/Object;", interfaces: List<String>? = null, fields: List<ImmutableField>? = null) =
            ImmutableClassDef(type, INSTANCE, superclass, interfaces, null, null, fields, methods)

        /** Shaped like 449's MainFeedStoryTrayBinderGroup.buildRowViewTypes: its trace name, then a row type added through the builder. */
        fun trayRows(type: String = ROWS, adds: Boolean = true): ClassDef {
            val code = listOfNotNull<Instruction>(
                string(0, TRAY_ROWS),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 3),
                if (adds) {
                    ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 3, 1, 0, 0, 0, ImmutableMethodReference(BUILDER, "ANA", listOf("I"), "V"))
                } else {
                    null
                },
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val parameters = listOf(BUILDER, "Ljava/lang/Object;", "Ljava/lang/Object;").map { ImmutableMethodParameter(it, null, null) }
            return classOf(
                type,
                listOf(ImmutableMethod(type, "buildRowViewTypes", parameters, "V", INSTANCE, null, null, ImmutableMethodImplementation(6, code, null, null))),
            )
        }

        /**
         * Shaped like 449's tray response parser and what it reads with: its keys, a tray item read
         * through the item parser's singleton, a null test skipping it, and the next key. The item
         * parser casts to the item, which keeps its reel type in an enum field.
         */
        fun trayClasses(
            tested: Boolean = true,
            remaining: Boolean = true,
            reelTypes: List<String> = listOf("USER_REEL") + SUGGESTED_REELS + MADE_REELS,
            itemInterface: String = TRAY_ITEM_INTF,
        ): List<ClassDef> {
            val tray = listOfNotNull<Instruction>(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference("Ljava/util/ArrayList;")),
                string(0, TRAY_REMAINING),
                if (remaining) {
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 3, 0, 0, 0, 0, ImmutableMethodReference(JSON, "A0B", listOf(JSON), "Ljava/util/ArrayList;"))
                } else {
                    null
                },
                if (remaining) ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0) else null,
                string(0, TRAY_TOKEN),
                string(0, TRAY_ITEMS),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, ImmutableFieldReference(PARSER, "A00", PARSER)),
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 2, 1, 3, 0, 0, 0,
                    ImmutableMethodReference(BASE, "parseFromJsonParser", listOf(JSON), "Ljava/lang/Object;"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                if (tested) ImmutableInstruction21t(Opcode.IF_EQZ, 1, 5) else null,
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 2, 2, 1, 0, 0, 0,
                    ImmutableMethodReference("Ljava/util/ArrayList;", "add", listOf("Ljava/lang/Object;"), "Z"),
                ),
                string(0, "personalization_features"),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
            )
            val json = listOf(ImmutableMethodParameter(JSON, null, null))
            val parse = listOf<Instruction>(
                ImmutableInstruction35c(Opcode.INVOKE_SUPER, 2, 1, 2, 0, 0, 0, ImmutableMethodReference(BASE, "parseFromJsonParser", listOf(JSON), "Ljava/lang/Object;")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(ITEM)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            val names = reelTypes.map { string(0, it) } + ImmutableInstruction10x(Opcode.RETURN_VOID)
            val static = AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value
            return listOf(
                classOf(TRAY, listOf(ImmutableMethod(TRAY, "unsafeParseFromJson", json, "Ljava/lang/Object;", INSTANCE, null, null, ImmutableMethodImplementation(4, tray, null, null)))),
                classOf(
                    PARSER,
                    listOf(ImmutableMethod(PARSER, "parseFromJsonParser", json, "Ljava/lang/Object;", INSTANCE, null, null, ImmutableMethodImplementation(3, parse, null, null))),
                    superclass = BASE,
                ),
                classOf(ITEM, emptyList(), interfaces = listOf(itemInterface), fields = listOf(ImmutableField(ITEM, "A03", REEL_TYPE, INSTANCE, null, null, null))),
                classOf(REEL_TYPE, listOf(ImmutableMethod(REEL_TYPE, "<clinit>", null, "V", static, null, null, ImmutableMethodImplementation(1, names, null, null))), superclass = "Ljava/lang/Enum;"),
            )
        }
    }
}

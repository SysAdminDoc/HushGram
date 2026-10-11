/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideReelsTabHookTest {
    private val tab = "Lfixture/Tab;"
    private val lists = "Lfixture/TabLists;"
    private val host = "Lfixture/TabHost;"
    private val builderName = "buildTabs"
    private val homeName = "homeTab"
    private val switchName = "switchTo"

    /** The hooks the patch writes are in the ReelsTab the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(SHOWN_TABS, TAB_TO_OPEN, PAGER_START)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * The list builder's return asks for the shown tabs, and its loop still lands on the ask; the home
     * tab's Reels is asked about before it's returned; the switch asks about its tab first, and its
     * read of the pager start flag goes through the extension.
     */
    @Test
    fun allThreeAsk() {
        val context = PatchContexts.of(classes())

        context.hideReelsTab()

        assertListAsked("the builder", context.method(lists, builderName))
        assertHomeAsked("the home tab", context.method(lists, homeName), tab)
        assertSwitchAsked("the switch", context.method(host, switchName), tab, register = 4)
        assertPagerStartAsked("the switch", context.method(host, switchName))
        assertTrue(
            "the home tab's Home return was touched",
            context.method(lists, homeName).code().count { it.referenceText() == TAB_TO_OPEN } == 1,
        )
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(enums = 0) to "one enum whose setup names $REELS with $REELS_MODULE, found 0",
            classes(enums = 2) to "found 2",
            classes(home = false) to "has no $HOME tab",
            classes(hosts = 0) to "one constructor reading \"$TAB_HOST_STATE\", found 0",
            classes(listsKept = 2) to "keeps 2 tab lists",
            classes(builderReturns = 2) to "returns in 2 places",
            classes(homeMethods = 0) to "returns Reels as a tab, found 0",
            classes(homeMethods = 2) to "returns Reels as a tab, found 2",
            classes(jumpToHomeReturn = true) to "something jumps to the return of Reels",
            classes(switches = 2) to "reporting \"$TAB_SWITCH_REPORT\", found 2",
            classes(jumpToSwitchStart = true) to "something jumps to the start",
            classes(pagerFlagReads = 0) to "one read of the pager start flag ${PAGER_START_FLAG.toString(16)}",
            classes(pagerFlagReads = 2) to "in $host->$switchName, found 2",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.hideReelsTab() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() in setOf(SHOWN_TABS, TAB_TO_OPEN, PAGER_START) } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the list builder, the home tab and the switch are found and hooked. */
    @Test
    fun eachDeclaredBuildGetsAllThreeHooks() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val enums = FixtureDex.classesHolding(bundle, REELS_MODULE).filter { it.superclass == "Ljava/lang/Enum;" }
                val hosts = FixtureDex.classesHolding(bundle, TAB_HOST_STATE)
                val builderTypes = hosts.flatMap { it.methods }.filter { it.name == "<init>" }.flatMap { it.code() }
                    .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .filter { it.returnType == LIST && SESSION in it.parameterTypes.map(CharSequence::toString) }
                    .map { it.definingClass }.toSet()
                val builders = FixtureDex.classes(bundle, builderTypes).values
                val context = PatchContexts.of((enums + hosts + builders).distinctBy { it.type })

                context.hideReelsTab()

                val tabType = enums.single { enum -> enum.methods.any { m -> m.name == "<clinit>" && m.code().any { it.string() == REELS } } }.type
                val asked = { type: String, hook: String ->
                    context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.referenceText() == hook } }
                }
                val listAsks = builders.flatMap { asked(it.type, SHOWN_TABS) }
                assertEquals("${bundle.name}: one list builder asks", 1, listAsks.size)
                assertListAsked("${bundle.name}: the list builder", listAsks.single())
                val homeAsks = builders.flatMap { asked(it.type, TAB_TO_OPEN) }
                assertEquals("${bundle.name}: one home tab asks", 1, homeAsks.size)
                assertHomeAsked("${bundle.name}: the home tab", homeAsks.single(), tabType)
                val switchAsks = hosts.flatMap { asked(it.type, TAB_TO_OPEN) }
                assertEquals("${bundle.name}: one switch asks", 1, switchAsks.size)
                val switch = switchAsks.single()
                assertTrue("${bundle.name}: the switch reports", switch.code().any { it.string() == TAB_SWITCH_REPORT })
                val tabParameter = switch.parameterTypes.indexOfFirst { it.toString() == tabType }
                val register = switch.implementation!!.registerCount - switch.parameterTypes.sumOf { width(it.toString()) } +
                    switch.parameterTypes.take(tabParameter).sumOf { width(it.toString()) }
                assertSwitchAsked("${bundle.name}: the switch", switch, tabType, register)
                assertPagerStartAsked("${bundle.name}: the switch", switch)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The builder ends in the ask, its answer and the return of it, and a jump that went to the return now goes to the ask. */
    private fun assertListAsked(what: String, method: MutableMethod) {
        val code = method.code()
        val asks = code.indices.filter { code[it].referenceText() == SHOWN_TABS }
        assertEquals("$what: asks", 1, asks.size)
        val ask = asks.single()
        val register = (code[ask] as RegisterRangeInstruction).startRegister
        assertEquals("$what: hands over one register", 1, (code[ask] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[ask + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[ask + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[ask + 2].opcode)
        assertEquals("$what: the return's register", register, (code[ask + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: returns anywhere else", 1, code.count { it.opcode == Opcode.RETURN_OBJECT })
        val targets = method.jumpTargets()
        assertTrue("$what: a jump skips the ask", ask + 1 !in targets && ask + 2 !in targets)
        assertTrue("$what: the loop no longer reaches the ask", ask in targets)
    }

    /** Reels is read, asked about, cast back to a tab and returned. */
    private fun assertHomeAsked(what: String, method: MutableMethod, tabType: String) {
        val code = method.code()
        val ask = code.indexOfFirst { it.referenceText() == TAB_TO_OPEN }
        val register = (code[ask] as RegisterRangeInstruction).startRegister
        assertEquals("$what: what comes before the ask", Opcode.SGET_OBJECT, code[ask - 1].opcode)
        assertEquals("$what: the tab asked about", register, (code[ask - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[ask + 1].opcode)
        assertEquals("$what: the cast", tabType, ((code[ask + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[ask + 3].opcode)
        assertEquals("$what: the return's register", register, (code[ask + 3] as OneRegisterInstruction).registerA)
    }

    /** First thing, the tab parameter goes to the extension and its answer, cast back to a tab, takes its place. */
    private fun assertSwitchAsked(what: String, method: MutableMethod, tabType: String, register: Int) {
        val code = method.code()
        assertEquals("$what: the ask", TAB_TO_OPEN, code[0].referenceText())
        assertEquals("$what: the register asked about", listOf(register, 1), (code[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: the answer's register", register, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", tabType, ((code[2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: asks", 1, code.count { it.referenceText() == TAB_TO_OPEN })
    }

    /** The flag's read, right after its move-result, goes to the extension, and its answer takes the read's place. */
    private fun assertPagerStartAsked(what: String, method: MutableMethod) {
        val code = method.code()
        val asks = code.indices.filter { code[it].referenceText() == PAGER_START }
        assertEquals("$what: pager start asks", 1, asks.size)
        val ask = asks.single()
        assertEquals("$what: what comes before the pager start ask", Opcode.MOVE_RESULT, code[ask - 1].opcode)
        val register = (code[ask - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the read handed over", listOf(register, 1), (code[ask] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$what: the pager start answer", Opcode.MOVE_RESULT, code[ask + 1].opcode)
        assertEquals("$what: the pager start answer's register", register, (code[ask + 1] as OneRegisterInstruction).registerA)
        val load = code.subList(0, ask).indexOfLast { it.opcode == Opcode.CONST_WIDE }
        assertTrue("$what: the pager start flag is what's read", load >= 0 && (code[load] as WideLiteralInstruction).wideLiteral == PAGER_START_FLAG)
    }

    // ---- stand-ins shaped like Instagram 449's -------------------------------------------------

    /**
     * The tab enum, whose setup names Reels with its module and Home; the class that builds the tab
     * list and names the home tab; and the tab host, whose constructor keeps the list and whose
     * switch files its report when there's no pager.
     */
    private fun classes(
        enums: Int = 1,
        home: Boolean = true,
        hosts: Int = 1,
        listsKept: Int = 1,
        builderReturns: Int = 1,
        homeMethods: Int = 1,
        jumpToHomeReturn: Boolean = false,
        switches: Int = 1,
        jumpToSwitchStart: Boolean = false,
        pagerFlagReads: Int = 1,
    ): List<ClassDef> {
        val tabEnums = (0 until enums).map { copy ->
            val type = if (copy == 0) tab else "Lfixture/OtherTab;"
            val setup = method(type, "<clinit>", emptyList(), "V", 2, static = true, body = """
                ${if (home) "const-string v0, \"$HOME\"" else ""}
                sput-object v1, $type->home:$type
                const-string v0, "$REELS_MODULE"
                const-string v0, "$REELS"
                sput-object v1, $type->reels:$type
                return-void
            """)
            classDef(type, listOf(setup), superclass = "Ljava/lang/Enum;", fields = listOf("home" to type, "reels" to type))
        }

        val secondReturn = if (builderReturns > 1) "return-object v0" else ""
        val builder = method(lists, builderName, listOf(SESSION, "Z"), LIST, 3, static = true, body = """
            if-eqz p1, :server
            const/4 v0, 0x0
            $secondReturn
            :done
            return-object v0
            :server
            new-instance v0, Ljava/util/ArrayList;
            const/4 v1, 0x0
            :loop
            add-int/lit8 v1, v1, 0x1
            const/4 v2, 0x5
            if-ge v1, v2, :done
            goto :loop
        """)
        val homeTabs = (0 until homeMethods).map { copy ->
            method(lists, if (copy == 0) homeName else "otherHomeTab", listOf(SESSION), tab, 1, static = true, body = """
                if-eqz p0, :reels
                sget-object v0, $tab->home:$tab
                return-object v0
                :reels
                sget-object v0, $tab->reels:$tab
                ${if (jumpToHomeReturn) ":back" else ""}
                return-object v0
                ${if (jumpToHomeReturn) "goto :back" else ""}
            """)
        }
        val listClass = classDef(lists, listOf(builder) + homeTabs)

        val keepSecond = if (listsKept > 1) """
            invoke-static { p1, v1 }, $lists->$builderName($SESSION Z)$LIST
            move-result-object v0
            iput-object v0, p0, $host->otherTabs:$LIST
        """ else ""
        val pagerFlagRead = """
            const-wide v0, ${PAGER_START_FLAG}L
            invoke-static { v0, v1 }, Lfixture/Config;->read(J)Z
            move-result v2
        """
        val hostMethods = (0 until hosts).map { copy ->
            method(host, "<init>", if (copy == 0) listOf(SESSION) else listOf(SESSION, "I"), "V", 2 + copy, constructor = true, body = """
                const-string v0, "$TAB_HOST_STATE"
                const/4 v1, 0x0
                invoke-static { p1, v1 }, $lists->$builderName($SESSION Z)$LIST
                move-result-object v0
                iput-object v0, p0, $host->tabs:$LIST
                $keepSecond
                return-void
            """)
        } + (0 until switches).map { copy ->
            method(host, if (copy == 0) switchName else "otherSwitch", listOf(host, tab, "Ljava/lang/String;", "Z", "Z"), "V", 3, static = true, body = """
                ${if (jumpToSwitchStart) ":start" else ""}
                const-string v0, "$TAB_SWITCH_REPORT"
                ${if (jumpToSwitchStart) "if-eqz p3, :start" else ""}
                ${if (copy == 0) pagerFlagRead.repeat(pagerFlagReads) else ""}
                return-void
            """)
        }
        return tabEnums + listClass + classDef(host, hostMethods, fields = listOf("tabs" to LIST, "otherTabs" to LIST))
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        constructor: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (constructor) flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { width(it) }
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

    private fun width(type: String): Int = if (type == "J" || type == "D") 2 else 1

    private fun BytecodePatchContext.method(type: String, name: String): MutableMethod =
        mutableClassDefBy(type).methods.single { it.name == name }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

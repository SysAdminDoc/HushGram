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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
import java.io.File

/** Hide the Home tab's three hooks: the back stack's root, the saved stack's tabs and the push that can start it again (#90). */
class HideHomeTabHookTest {
    private val tab = "Lfixture/Tab;"
    private val lists = "Lfixture/TabLists;"
    private val host = "Lfixture/TabHost;"
    private val navigation = "Lfixture/Navigation;"
    private val bundle = "Landroid/os/Bundle;"

    /** The hook the patch writes is in the ReelsTab the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_ROOT.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOME_ROOT is not in the extension: $declared", HOME_ROOT.substringAfter("->") in declared)
    }

    /**
     * Both of the root's returns ask, the saved stack's tab is asked about as it's made, the push
     * asks about both its tabs first, and the start tab and the reset, which only reach Home through
     * the root, are left as they were.
     */
    @Test
    fun allThreeAsk() {
        val context = PatchContexts.of(classes())

        context.hideHomeTab(context.findHomeTab(context.findReelsTab()))

        assertRootAsked("the root", context.method(lists, "rootTab"), tab)
        assertRestoreAsked("the saved stack", context.method(navigation, "<init>"), tab)
        assertPushAsked("the push", context.method(navigation, "push"), tab, listOf(3, 4))
        for ((type, name) in listOf(lists to "startTab", navigation to "reset", lists to "homeTab")) {
            assertEquals("$name asks", 0, context.method(type, name).code().count { it.referenceText() == HOME_ROOT })
        }
    }

    /**
     * With the root folded into the home tab method, as on 452.1's x86 build, that method is the root:
     * each of its returns asks, and the saved stack and the push ask as they always do.
     */
    @Test
    fun aRootFoldedIntoTheHomeTabIsTheRoot() {
        val context = PatchContexts.of(classes(rootInHomeTab = true))

        context.hideHomeTab(context.findHomeTab(context.findReelsTab()))

        assertRootAsked("the home tab as the root", context.method(lists, "homeTab"), tab)
        assertRestoreAsked("the saved stack", context.method(navigation, "<init>"), tab)
        assertPushAsked("the push", context.method(navigation, "push"), tab, listOf(3, 4))
    }

    /** A build the patch can't prove fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantProveFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(roots = 0) to "returns FEED as a tab, found 0",
            classes(roots = 2) to "returns FEED as a tab, found 2",
            classes(navigationMade = false) to "takes it and the tab list and keeps a back stack, found 0",
            classes(startFallsBack = false) to "doesn't fall back to $lists->rootTab",
            classes(startNamesHome = true) to "names FEED itself",
            classes(savedNames = 0) to "turns 0 saved names into tabs",
            classes(savedNames = 2) to "turns 2 saved names into tabs",
            classes(resetNamesHome = true) to "$navigation->reset clears the back stack and names FEED itself",
            classes(resetStartsAgain = false) to "$navigation->reset clears the back stack without starting it again",
            classes(pushTabs = 1) to "clears the back stack and takes two tabs, found 0",
            classes(jumpToPushStart = true) to "something jumps to the start of $navigation->push",
            classes(jumpPastSavedTab = true) to "something jumps past the saved tab",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) {
                context.hideHomeTab(context.findHomeTab(context.findReelsTab()))
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == HOME_ROOT } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the root, the saved stack and the push are found, proved and hooked. */
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

    @Test
    fun eachOtherBuildGetsAllThreeHooksToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val enums = FixtureDex.classesHolding(bundle, REELS_MODULE).filter { it.superclass == "Ljava/lang/Enum;" }
        val hosts = FixtureDex.classesHolding(bundle, TAB_HOST_STATE)
        val constructors = hosts.flatMap { it.methods }.filter { it.name == "<init>" }.flatMap { it.code() }
        val builderTypes = constructors.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.returnType == LIST && SESSION in it.parameterTypes.map(CharSequence::toString) }
            .map { it.definingClass }.toSet()
        val madeTypes = constructors.filter { it.opcode == Opcode.NEW_INSTANCE }
            .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }.toSet()
        val builders = FixtureDex.classes(bundle, builderTypes).values
        val made = FixtureDex.classes(bundle, madeTypes).values
        val context = PatchContexts.of((enums + hosts + builders + made).distinctBy { it.type })

        val found = context.findHomeTab(context.findReelsTab())
        context.hideHomeTab(found)

        val tabType = found.tabType
        val asking = (builders + made).map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
            .filter { method -> method.code().any { it.referenceText() == HOME_ROOT } }
        assertEquals("$label: three methods ask, ${asking.map { "${it.definingClass}->${it.name}" }}", 3, asking.size)
        val root = asking.single { it.definingClass in builderTypes }
        assertRootAsked("$label: the root", root, tabType)
        val restore = asking.single { it.name == "<init>" }
        assertRestoreAsked("$label: the saved stack", restore, tabType)
        assertTrue("$label: the navigation class keeps a back stack", context.mutableClassDefBy(restore.definingClass).fields.any { it.type == BACK_STACK })
        val push = asking.single { it !== root && it !== restore }
        assertEquals("$label: the push is the navigation class's", restore.definingClass, push.definingClass)
        assertPushAsked("$label: the push", push, tabType, found.pushTabs)
    }

    /** Every return of the root is the ask on its register, its answer, the cast back to a tab and the return. */
    private fun assertRootAsked(what: String, method: MutableMethod, tabType: String) {
        val code = method.code()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
        assertTrue("$what: returns", returns.isNotEmpty())
        assertEquals("$what: asks once per return", returns.size, code.count { it.referenceText() == HOME_ROOT })
        for (at in returns) {
            val register = (code[at] as OneRegisterInstruction).registerA
            assertEquals("$what: the cast before the return at $at", tabType, ((code[at - 1] as ReferenceInstruction).reference as TypeReference).type)
            assertEquals("$what: the answer at $at", Opcode.MOVE_RESULT_OBJECT, code[at - 2].opcode)
            assertEquals("$what: the answer's register at $at", register, (code[at - 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: the ask at $at", HOME_ROOT, code[at - 3].referenceText())
            assertEquals("$what: the register asked about at $at", listOf(register, 1),
                (code[at - 3] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        }
    }

    /** Right after the saved name becomes a tab, the tab is asked about, and the answer cast back takes its place. */
    private fun assertRestoreAsked(what: String, method: MutableMethod, tabType: String) {
        val code = method.code()
        val asks = code.indices.filter { code[it].referenceText() == HOME_ROOT }
        assertEquals("$what: asks", 1, asks.size)
        val ask = asks.single()
        val made = code[ask - 2].methodReference()
        assertTrue("$what: the ask follows the saved name's turn into a tab", made != null && made.name == "valueOf" && made.definingClass == tabType)
        assertEquals("$what: the tab", Opcode.MOVE_RESULT_OBJECT, code[ask - 1].opcode)
        val register = (code[ask - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the register asked about", listOf(register, 1), (code[ask] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$what: the answer", register, (code[ask + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", tabType, ((code[ask + 2] as ReferenceInstruction).reference as TypeReference).type)
    }

    /** First thing, each tab parameter goes to the extension and its answer, cast back to a tab, takes its place. */
    private fun assertPushAsked(what: String, method: MutableMethod, tabType: String, registers: List<Int>) {
        val code = method.code()
        assertEquals("$what: asks", registers.size, code.count { it.referenceText() == HOME_ROOT })
        registers.forEachIndexed { n, register ->
            val at = n * 3
            assertEquals("$what: the ask about v$register", HOME_ROOT, code[at].referenceText())
            assertEquals("$what: the register asked about", listOf(register, 1), (code[at] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
            assertEquals("$what: the answer", register, (code[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the cast", tabType, ((code[at + 2] as ReferenceInstruction).reference as TypeReference).type)
        }
    }

    // ---- stand-ins shaped like Instagram 451's -------------------------------------------------

    /**
     * What [findReelsTab] needs (the tab enum, the list builder's class with its home tab, the tab
     * host with its switch), and the parts Hide the Home tab adds: the builder's class names Home as
     * the root and has a start tab falling back to it, and the host makes a navigation class whose
     * constructor turns a saved name into a tab and whose reset and push clear the back stack and
     * start it again from the root.
     */
    private fun classes(
        roots: Int = 1,
        navigationMade: Boolean = true,
        startFallsBack: Boolean = true,
        startNamesHome: Boolean = false,
        savedNames: Int = 1,
        resetNamesHome: Boolean = false,
        resetStartsAgain: Boolean = true,
        pushTabs: Int = 2,
        jumpToPushStart: Boolean = false,
        jumpPastSavedTab: Boolean = false,
        rootInHomeTab: Boolean = false,
    ): List<ClassDef> {
        // 452.1's x86 build has no root of its own: the start tab and the resets fall back to the home tab method.
        fun callRoot(self: String, session: String) =
            if (rootInHomeTab) "invoke-static { $session }, $lists->homeTab($SESSION)$tab"
            else "invoke-virtual { $self, $session }, $lists->rootTab($SESSION)$tab"
        val setup = method(tab, "<clinit>", emptyList(), "V", 2, static = true, body = """
            const-string v0, "$HOME"
            sput-object v1, $tab->home:$tab
            const-string v0, "$REELS_MODULE"
            const-string v0, "$REELS"
            sput-object v1, $tab->reels:$tab
            return-void
        """)
        val tabEnum = classDef(tab, listOf(setup), superclass = "Ljava/lang/Enum;", fields = listOf("home" to tab, "reels" to tab))

        val builder = method(lists, "buildTabs", listOf(SESSION, "Z"), LIST, 1, static = true, body = """
            const/4 v0, 0x0
            return-object v0
        """)
        val homeTab = method(lists, "homeTab", listOf(SESSION), tab, 1, static = true, body = """
            if-eqz p0, :reels
            sget-object v0, $tab->home:$tab
            return-object v0
            :reels
            sget-object v0, $tab->reels:$tab
            return-object v0
        """)
        val rootTabs = (0 until if (rootInHomeTab) 0 else roots).map { copy ->
            method(lists, if (copy == 0) "rootTab" else "otherRootTab", listOf(SESSION), tab, 1, body = """
                if-eqz p1, :home
                invoke-static { p1 }, $lists->homeTab($SESSION)$tab
                move-result-object v0
                return-object v0
                :home
                sget-object v0, $tab->home:$tab
                return-object v0
            """)
        }
        val fallback = if (startFallsBack) "${callRoot("p0", "p1")}\nmove-result-object v0" else "const/4 v0, 0x0"
        val startTab = method(lists, "startTab", listOf(SESSION, "Ljava/lang/String;", LIST), tab, 1, body = """
            if-eqz p2, :fallback
            invoke-static { p2 }, $tab->valueOf(Ljava/lang/String;)$tab
            move-result-object v0
            return-object v0
            :fallback
            ${if (startNamesHome) "sget-object v0, $tab->home:$tab" else ""}
            $fallback
            return-object v0
        """)
        val listClass = classDef(lists, listOf(builder, homeTab, startTab) + rootTabs)

        val made = if (navigationMade) """
            new-instance v1, $navigation
            invoke-direct { v1, p2, p0, v0, p1 }, $navigation-><init>($bundle$host$LIST$SESSION)V
        """ else ""
        val hostInit = method(host, "<init>", listOf(SESSION, bundle), "V", 2, constructor = true, body = """
            const-string v0, "$TAB_HOST_STATE"
            const/4 v1, 0x0
            invoke-static { p1, v1 }, $lists->buildTabs($SESSION Z)$LIST
            move-result-object v0
            iput-object v0, p0, $host->tabs:$LIST
            $made
            return-void
        """)
        val switch = method(host, "switchTo", listOf(host, tab, "Ljava/lang/String;", "Z", "Z"), "V", 3, static = true, body = """
            const-string v0, "$TAB_SWITCH_REPORT"
            const-wide v0, ${PAGER_START_FLAG}L
            invoke-static { v0, v1 }, Lfixture/Config;->read(J)Z
            move-result v2
            return-void
        """)
        val hostClass = classDef(host, listOf(hostInit, switch), fields = listOf("tabs" to LIST))

        val saved = """
            const-string v0, "SEARCH"
            invoke-static { v0 }, $tab->valueOf(Ljava/lang/String;)$tab
            move-result-object v0
            ${if (jumpPastSavedTab) ":pushed" else ""}
            invoke-virtual { v1, v0 }, $BACK_STACK->push(Ljava/lang/Object;)Ljava/lang/Object;
            ${if (jumpPastSavedTab) "if-eqz p1, :pushed" else ""}
        """
        val navigationInit = method(navigation, "<init>", listOf(bundle, host, LIST, SESSION), "V", 2, constructor = true, body = """
            invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
            new-instance v1, $BACK_STACK
            invoke-direct { v1 }, $BACK_STACK-><init>()V
            if-eqz p1, :fresh
            ${saved.repeat(savedNames)}
            :fresh
            const/4 v0, 0x0
            invoke-virtual { v0, p4, v0, p3 }, $lists->startTab(${SESSION}Ljava/lang/String;$LIST)$tab
            move-result-object v0
            invoke-virtual { v1, v0 }, $BACK_STACK->push(Ljava/lang/Object;)Ljava/lang/Object;
            iput-object v1, p0, $navigation->stack:$BACK_STACK
            return-void
        """)
        val startAgain = if (resetStartsAgain) "${callRoot("v1", "v1")}\nmove-result-object v1" else "move-object v1, p1"
        val reset = method(navigation, "reset", listOf(tab), "V", 2, body = """
            iget-object v0, p0, $navigation->stack:$BACK_STACK
            invoke-virtual { v0 }, Ljava/util/AbstractCollection;->clear()V
            const/4 v1, 0x0
            ${if (resetNamesHome) "sget-object v1, $tab->home:$tab" else ""}
            $startAgain
            invoke-virtual { v0, v1 }, $BACK_STACK->push(Ljava/lang/Object;)Ljava/lang/Object;
            return-void
        """)
        val pushParameters = List(pushTabs) { tab } + listOf("Z", "Z", "Z")
        val push = method(navigation, "push", pushParameters, "V", 2, body = """
            ${if (jumpToPushStart) ":start" else ""}
            iget-object v0, p0, $navigation->stack:$BACK_STACK
            if-eqz v0, :start_again
            ${if (jumpToPushStart) "goto :start" else ""}
            :start_again
            invoke-virtual { v0 }, Ljava/util/AbstractCollection;->clear()V
            const/4 v1, 0x0
            ${callRoot("v1", "v1")}
            move-result-object v1
            invoke-virtual { v0, v1 }, $BACK_STACK->push(Ljava/lang/Object;)Ljava/lang/Object;
            return-void
        """)
        val navigationClass = classDef(navigation, listOf(navigationInit, reset, push), fields = listOf("stack" to BACK_STACK))
        return listOf(tabEnum, listClass, hostClass, navigationClass)
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

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
}

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
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
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

/** The Search button on Home's header: what its two stubs call, found on every 450 build. */
class SearchHeaderHookTest {
    private val tab = "Lfixture/Tab;"
    private val host = "Lfixture/TabHost;"
    private val pager = "Landroidx/viewpager2/widget/ViewPager2;"

    /** Both stubs are in the SearchHeaderButton the bundle ships, public and static. */
    @Test
    fun theStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(SEARCH_BUTTON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("the $SEARCH_SELECT_STUB stub is not in the extension: $declared", "$SEARCH_SELECT_STUB(Ljava/lang/Object;Ljava/lang/Object;)I" in declared)
        assertTrue("the $SEARCH_PAGING_STUB stub is not in the extension: $declared", "$SEARCH_PAGING_STUB(Ljava/lang/Object;)I" in declared)
    }

    /** A build the finder can't read fails at patch time, saying what it found, before a stub is written. */
    @Test
    fun aBuildTheFinderCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(activity = false) to "this Instagram build has no $MAIN_ACTIVITY",
            classes(hostFields = 2) to "expected $MAIN_ACTIVITY to keep one tab host $host, found 2",
            classes(hostFields = 0) to "expected $MAIN_ACTIVITY to keep one tab host $host, found 0",
            classes(selects = 0) to "taking a tab, a String and a boolean that calls switchTo, found 0",
            classes(selects = 2) to "taking a tab, a String and a boolean that calls switchTo, found 2",
            classes(pagers = 0) to "expected $host to keep one $pager, found 0",
            classes(publicPager = false) to "$host's pager pager isn't public",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes + ExtensionDex.classDef(SEARCH_BUTTON))
            val failure = assertThrows(PatchException::class.java) { context.findSearchButton(hooks(context)) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val stubs = context.mutableClassDefBy(SEARCH_BUTTON).methods.filter { it.name == SEARCH_SELECT_STUB || it.name == SEARCH_PAGING_STUB }
            assertTrue("$expected: a stub was written", stubs.all { stub -> stub.code().map { it.opcode } == listOf(Opcode.CONST_4, Opcode.RETURN) })
        }
    }

    /**
     * In each of the seven 450 builds the main activity keeps one tab host, the host's one public
     * switch to a tab picked from code calls the switch the tab list hook asks at, the activity calls
     * that switch itself, and the host keeps one pager. The stubs are written with their locals below
     * their parameters, so nothing they write lands on a parameter they read after.
     */
    @Test
    fun everyBuildGetsTheSearchButtonsStubs() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the six others", 7, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name ?: bundle.name
            val enums = FixtureDex.classesHolding(bundle, REELS_MODULE).filter { it.superclass == "Ljava/lang/Enum;" }
            val hosts = FixtureDex.classesHolding(bundle, TAB_HOST_STATE)
            val builderTypes = hosts.flatMap { it.methods }.filter { it.name == "<init>" }.flatMap { it.code() }
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .filter { it.returnType == LIST && SESSION in it.parameterTypes.map(CharSequence::toString) }
                .map { it.definingClass }.toSet()
            val builders = FixtureDex.classes(bundle, builderTypes).values
            val activity = FixtureDex.classes(bundle, setOf(MAIN_ACTIVITY)).values.single()
            val context = PatchContexts.of((enums + hosts + builders + activity + ExtensionDex.classDef(SEARCH_BUTTON)).distinctBy { it.type })

            val found = context.findReelsTab()
            val hook = context.findSearchButton(found)
            val hostType = found.switch.definingClass
            assertEquals("$name: the tab enum", found.tabType, hook.tabType)
            assertTrue("$name: the activity's host ${hook.host}", hook.host.startsWith("$MAIN_ACTIVITY->") && hook.host.endsWith(":$hostType"))
            assertTrue("$name: the host's pager ${hook.pager}", hook.pager.startsWith("$hostType->") && hook.pager.endsWith(":$pager"))
            assertTrue("$name: the host's switch ${hook.select}", hook.select.startsWith("$hostType->") && hook.select.endsWith("(${found.tabType}Ljava/lang/String;Z)V"))
            val calls = activity.methods.count { method -> method.code().any { it.referenceText() == hook.select } }
            assertTrue("$name: $MAIN_ACTIVITY switches tabs through ${hook.select} itself", calls >= 1)

            context.addSearchButton(hook)

            val selectMethod = context.mutableClassDefBy(SEARCH_BUTTON).methods.single { it.name == SEARCH_SELECT_STUB }
            val select = selectMethod.code()
            assertEquals("$name: three locals and two parameters", listOf(5, 3), listOf(selectMethod.implementation!!.registerCount, selectMethod.localRegisterCount()))
            assertEquals(
                "$name: select stub",
                listOf(
                    Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ,
                    Opcode.CHECK_CAST, Opcode.CONST_4, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.CONST_4, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN,
                ),
                select.map { it.opcode },
            )
            assertEquals("$name: asks about the activity", listOf(MAIN_ACTIVITY, 3), (select[0] as TwoRegisterInstruction).let { listOf((select[0].reference() as TypeReference).type, it.registerB) })
            assertEquals("$name: asks about the tab", listOf(found.tabType, 4), (select[2] as TwoRegisterInstruction).let { listOf((select[2].reference() as TypeReference).type, it.registerB) })
            assertEquals("$name: reads the host", hook.host, select[5].referenceText())
            assertEquals("$name: calls the switch", hook.select, select[10].referenceText())
            val call = select[10] as FiveRegisterInstruction
            assertEquals("$name: host, tab, no String, false", listOf(0, 4, 1, 2), listOf(call.registerC, call.registerD, call.registerE, call.registerF))
            assertLocalsStayOffParameters("$name: select stub", select, locals = 3)

            val pagingMethod = context.mutableClassDefBy(SEARCH_BUTTON).methods.single { it.name == SEARCH_PAGING_STUB }
            val paging = pagingMethod.code()
            assertEquals("$name: one local and one parameter", listOf(2, 1), listOf(pagingMethod.implementation!!.registerCount, pagingMethod.localRegisterCount()))
            assertEquals(
                "$name: paging stub",
                listOf(
                    Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.IF_EQZ,
                    Opcode.CONST_4, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN,
                ),
                paging.map { it.opcode },
            )
            assertEquals("$name: reads the host from the activity", listOf(hook.host, 1), listOf(paging[3].referenceText(), (paging[3] as TwoRegisterInstruction).registerB))
            assertEquals("$name: reads the pager from the host", listOf(hook.pager, 0), listOf(paging[5].referenceText(), (paging[5] as TwoRegisterInstruction).registerB))
            assertLocalsStayOffParameters("$name: paging stub", paging, locals = 1)
        }
    }

    /** Every register a stub writes is a local, except a cast, which narrows the parameter it names. */
    private fun assertLocalsStayOffParameters(what: String, code: List<Instruction>, locals: Int) {
        for (instruction in code) {
            if (!instruction.opcode.setsRegister()) continue
            val written = (instruction as OneRegisterInstruction).registerA
            if (instruction.opcode == Opcode.CHECK_CAST) {
                assertTrue("$what: a cast of v$written, a local", written >= locals)
            } else {
                assertTrue("$what: ${instruction.opcode.name} writes v$written, a parameter's register", written < locals)
            }
        }
    }

    // ---- stand-ins -----------------------------------------------------------------------------

    /** The hooks the tab list finder would hand over: only the switch and the tab type are read. */
    private fun hooks(context: BytecodePatchContext): ReelsTabHooks {
        val switch = context.classDefByOrNull(host)!!.methods.single { it.name == "switchTo" }
        return ReelsTabHooks(tab, switch, 0, switch, 0, switch, 1)
    }

    private fun classes(
        activity: Boolean = true,
        hostFields: Int = 1,
        selects: Int = 1,
        pagers: Int = 1,
        publicPager: Boolean = true,
    ): List<ClassDef> {
        val switch = method(host, "switchTo", listOf(host, tab, "Ljava/lang/String;", "Z", "Z"), "V", 0, static = true, body = "return-void")
        val picks = (0 until selects).map { copy ->
            method(host, if (copy == 0) "pick" else "pickAgain", listOf(tab, "Ljava/lang/String;", "Z"), "V", 1, body = """
                const/4 v0, 0x0
                invoke-static { p0, p1, p2, p3, v0 }, $host->switchTo($host$tab${"Ljava/lang/String;"}ZZ)V
                return-void
            """)
        }
        val hostFields0 = (0 until pagers).map { copy -> ImmutableField(host, if (copy == 0) "pager" else "pager$copy", pager, if (publicPager) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null) }
        val hostClass = ImmutableClassDef(host, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, hostFields0, listOf(switch) + picks)
        val tabClass = ImmutableClassDef(tab, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Enum;", null, null, null, emptyList(), emptyList())
        val activityClass = ImmutableClassDef(
            MAIN_ACTIVITY, AccessFlags.PUBLIC.value, "Landroid/app/Activity;", null, null, null,
            (0 until hostFields).map { copy -> ImmutableField(MAIN_ACTIVITY, "host$copy", host, AccessFlags.PUBLIC.value, null, null, null) },
            emptyList(),
        )
        return listOf(hostClass, tabClass) + if (activity) listOf(activityClass) else emptyList()
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, static: Boolean = false, body: String): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
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

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.tabswipe

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stop swiping between tabs: the main tabs' pager goes to TabSwipe once it's stored, and each touch
 * method of a pager's own list asks TabSwipe right after reading the input flag. Anything the patch
 * can't tell apart fails it before an instruction changes.
 */
class StopTabSwipingHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(TAB_SWIPE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(MAIN_PAGER, INPUT)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun theStoreAndBothTouchChecksAskTheExtension() {
        val context = PatchContexts.of(classes())

        val site = context.findTabSwiping()
        context.stopTabSwiping(site)

        assertEquals(TABS, site.owner)
        assertEquals(LIST, site.list)
        val setup = context.mutableClassDefBy(TABS).methods.single { it.name == "A05" }
        assertPagerHandedOver("stand-in", setup)
        val list = context.mutableClassDefBy(LIST)
        TOUCH_METHODS.forEach { name -> assertFlagAsked("stand-in $name", list.methods.single { it.name == name }) }
    }

    @Test
    fun twoPagerStoresFailThePatchUnchanged() {
        refusesUnchanged("stores 2 pagers") { classes(stores = 2) }
    }

    @Test
    fun aSetupWithoutBothLogLinesFailsThePatch() {
        refusesUnchanged("found 0") { classes(strings = listOf(PAGER_MISSING)) }
    }

    @Test
    fun aListReadingTheFlagTwiceFailsThePatchUnchanged() {
        refusesUnchanged("2 times") { classes(flagReads = 2) }
    }

    @Test
    fun aPagerWithoutItsListFailsThePatch() {
        refusesUnchanged("one list class") { classes(makesList = false) }
    }

    /** On each declared build: the setup's one store, the pager's list, both touch checks asking. */
    @Test
    fun eachDeclaredBuildStopsTheMainTabsSwipe() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, PAGER_BINDER)
                val pager = FixtureDex.classes(bundle, setOf(VIEW_PAGER)).values.single()
                val made = pager.methods.flatMap { it.instructions() }.filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet()
                val kept = (holders + pager + FixtureDex.classes(bundle, made).values).distinctBy { it.type }
                val context = PatchContexts.of(FixtureDex.withStringPools(bundle, kept))

                val site = context.findTabSwiping()
                context.stopTabSwiping(site)

                val setup = context.mutableClassDefBy(site.owner).methods.single {
                    it.name == site.setup && it.parameterTypes.joinToString("", "(", ")") + it.returnType == site.setupShape
                }
                assertPagerHandedOver(bundle.name, setup)
                val list = context.mutableClassDefBy(site.list)
                TOUCH_METHODS.forEach { name ->
                    assertFlagAsked("${bundle.name} $name", list.methods.single { it.name == name && it.parameterTypes.size == 1 })
                }
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refusesUnchanged(reason: String, build: () -> List<ClassDef>) {
        val context = PatchContexts.of(build())
        val before = listOf(TABS, LIST).associateWith { type ->
            context.classDefByOrNull(type)?.methods?.associate { it.name to it.instructions().size }
        }
        val refusal = assertThrows(PatchException::class.java) { context.stopTabSwiping(context.findTabSwiping()) }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        val after = listOf(TABS, LIST).associateWith { type ->
            context.classDefByOrNull(type)?.methods?.associate { it.name to it.instructions().size }
        }
        assertEquals(before, after)
    }

    /** The instruction after the one pager store hands the stored register to mainPager. */
    private fun assertPagerHandedOver(what: String, setup: Method) {
        val code = setup.instructions()
        val store = code.indexOfFirst {
            it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference.toString().endsWith(":$VIEW_PAGER")
        }
        assertTrue("$what: no pager store", store >= 0)
        val call = code[store + 1]
        assertEquals("$what: the hook", MAIN_PAGER, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the register handed over", (code[store] as TwoRegisterInstruction).registerA, (call as RegisterRangeInstruction).startRegister)
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == MAIN_PAGER })
    }

    /** Right after the flag's read: the hook with this and the flag, its answer back in the flag's register. */
    private fun assertFlagAsked(what: String, method: Method) {
        val code = method.instructions()
        val read = code.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN && it.isFlag() }
        val flag = (code[read] as TwoRegisterInstruction).registerA
        val call = code[read + 1] as FiveRegisterInstruction
        assertEquals("$what: the hook", INPUT, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: this", method.implementation!!.registerCount - 2, call.registerC)
        assertEquals("$what: the flag", flag, call.registerD)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[read + 2].opcode)
        assertEquals("$what: the answer's register", flag, (code[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == INPUT })
    }

    private fun Instruction.isFlag() = (this as ReferenceInstruction).reference.toString().startsWith("$VIEW_PAGER->")

    private companion object {
        const val TABS = "Lfixture/MainTabs;"
        const val LIST = "Lfixture/PagerList;"
        const val TAB = "Lfixture/Tab;"
        val FLAG = ImmutableFieldReference(VIEW_PAGER, "A0A", "Z")
        val PAGER_FIELD = ImmutableFieldReference(TABS, "A00", VIEW_PAGER)
        val LIST_PAGER = ImmutableFieldReference(LIST, "A00", VIEW_PAGER)

        fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<Instruction>) =
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            )

        /** Shaped like 450's main tab setup, ViewPager2 and the pager's list. */
        fun classes(
            stores: Int = 1,
            strings: List<String> = listOf(PAGER_MISSING, PAGER_BINDER),
            flagReads: Int = 1,
            makesList: Boolean = true,
        ): List<ClassDef> {
            val public = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val static = public or AccessFlags.STATIC.value
            val setupCode = strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                List(stores) { ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 2, PAGER_FIELD) } +
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
            val tabs = ImmutableClassDef(
                TABS, public, "Ljava/lang/Object;", null, null, null,
                listOf(ImmutableField(TABS, "A00", VIEW_PAGER, AccessFlags.PUBLIC.value, null, null, null)),
                listOf(method(TABS, "A05", listOf(TABS, TAB, "Ljava/lang/String;", "Z", "Z"), "V", static, 8, setupCode)),
            )
            val pagerMethods = listOf(
                method(VIEW_PAGER, "setUserInputEnabled", listOf("Z"), "V", AccessFlags.PUBLIC.value, 3, listOf(
                    ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 2, 1, FLAG),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                )),
                method(VIEW_PAGER, "A01", emptyList(), "V", AccessFlags.PRIVATE.value, 2, listOf(
                    ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(if (makesList) LIST else "Ljava/lang/Object;")),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                )),
            )
            val pager = ImmutableClassDef(
                VIEW_PAGER, public, "Landroid/view/ViewGroup;", null, null, null,
                listOf(ImmutableField(VIEW_PAGER, "A0A", "Z", AccessFlags.PUBLIC.value, null, null, null)),
                pagerMethods,
            )
            fun touch(name: String) = method(LIST, name, listOf(MOTION_EVENT), "Z", public, 3,
                listOf(ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, LIST_PAGER)) +
                    List(flagReads) { ImmutableInstruction22c(Opcode.IGET_BOOLEAN, 0, 0, FLAG) } +
                    listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)))
            val list = ImmutableClassDef(
                LIST, public, RECYCLER_VIEW, null, null, null,
                listOf(ImmutableField(LIST, "A00", VIEW_PAGER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)),
                TOUCH_METHODS.map(::touch),
            )
            return listOf(tabs, pager, list)
        }
    }
}

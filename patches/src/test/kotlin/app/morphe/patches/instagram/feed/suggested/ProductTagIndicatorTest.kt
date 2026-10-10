/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductTagIndicatorTest {
    /** The hook the patch writes is in the ProductTags the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PRODUCT_TAGS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PRODUCT_TAG_INDICATOR is not in the extension: $declared", PRODUCT_TAG_INDICATOR.substringAfter("->") in declared)
        assertEquals(PRODUCTS, ExtensionDex.stringConstant(PRODUCT_TAGS, "PRODUCTS"))
    }

    @Test
    fun theCheckIsFound() {
        val found = PatchContexts.of(classes()).findProductTagIndicator()

        assertEquals(DECIDER, found.type)
        assertEquals("A06", found.name)
        assertEquals(listOf(SESSION, MEDIA_TYPE, MEDIA_TYPE), found.parameters)
        assertEquals(INDICATOR, found.returnType)
        assertEquals(3, found.branchAt)
        assertEquals(0, found.register)
    }

    /** The check's answer goes past the extension before the branch, and a no still goes on to the next indicator. */
    @Test
    fun theAnswerGoesPastTheExtension() {
        val context = PatchContexts.of(classes())
        val found = context.findProductTagIndicator()

        found.write(context)

        assertHooked("stand-in", context, found)
    }

    @Test
    fun noIndicatorEnumFailsThePatch() = refused("found []", classes(names = listOf("NONE", "PEOPLE", PRODUCTS)))

    @Test
    fun twoIndicatorEnumsFailThePatch() = refused(OTHER_INDICATOR, classes(secondEnum = true))

    @Test
    fun theProductsConstantReturnedTwiceFailsThePatch() = refused("found 2", classes(secondSite = true))

    @Test
    fun aStaticDeciderFailsThePatch() = refused("isn't an instance method", classes(deciderFlags = PUBLIC or AccessFlags.STATIC.value))

    @Test
    fun aDeciderWithoutAPostFailsThePatch() = refused("isn't an instance method", classes(parameters = listOf(SESSION, "Ljava/lang/Object;")))

    @Test
    fun aCheckThatIsntStaticFailsThePatch() = refused("static check", classes(checkOpcode = Opcode.INVOKE_VIRTUAL))

    @Test
    fun aNoThatGoesElsewhereFailsThePatch() = refused("next indicator", classes(branchOffset = 8))

    @Test
    fun anAnswerPastV15FailsThePatch() = refused("past v15", classes(answerRegister = 16))

    /** Another branch or switch landing on the hooked branch could bring a reference where the hook passes an int. */
    @Test
    fun aSecondWayIntoTheBranchFailsThePatch() = refused("another way into the branch", classes(entry = Entry.IF))

    @Test
    fun aSwitchIntoTheBranchFailsThePatch() = refused("another way into the branch", classes(entry = Entry.SWITCH))

    /** The same jumps, aimed anywhere else in the method, leave the branch alone. */
    @Test
    fun aJumpPastTheBranchIsFine() {
        val found = PatchContexts.of(classes(entry = Entry.ELSEWHERE)).findProductTagIndicator()
        assertEquals("the if-eqz, behind the jump that came first", 4, found.branchAt)
    }

    /**
     * In each declared build one method answers the products indicator, after a static check's
     * yes, and after the patch that yes goes past the extension. On 450's 385611438 that's
     * LX/04pB->A06.
     */
    @Test
    fun eachDeclaredBuildHooksTheProductsIndicator() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooksTheProductsIndicator(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other arm64 builds of each declared version. */
    @Test
    fun eachOtherBuildHooksTheProductsIndicator() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hooksTheProductsIndicator(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /**
     * Slices the fixture the way the finder reads it: the classes holding the products name, then
     * every class reading the indicator enum's products constant, so a second method answering it
     * anywhere in the app would be there to find.
     */
    private fun hooksTheProductsIndicator(bundle: File, label: String) {
        val holders = FixtureDex.classesHolding(bundle, PRODUCTS)
        val enum = holders.filter { holder ->
            holder.superclass == "Ljava/lang/Enum;" && holder.methods.any { method ->
                method.implementation?.instructions?.any { (it.reference() as? StringReference)?.string == SHOPPING_ADS } == true
            }
        }
        assertEquals("$label: indicator enums", 1, enum.size)
        val writes = enum.single().methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
        val named = writes.indexOfFirst { (it.reference() as? StringReference)?.string == PRODUCTS }
        val constant = writes.drop(named).first { it.opcode == Opcode.SPUT_OBJECT }.reference() as FieldReference
        val held = holders.mapTo(HashSet()) { it.type }
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.definingClass == constant.definingClass && it.name == constant.name }) return@forEach
            for (classDef in dex.classes) {
                val reads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.SGET_OBJECT && it.reference()?.toString() == constant.toString()
                    } == true
                }
                if (reads && classDef.type !in held) readers += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders + readers))

        val found = context.findProductTagIndicator()
        found.write(context)

        assertEquals("$label: answers the indicator enum", enum.single().type, found.returnType)
        assertHooked("$label ${found.type}->${found.name}", context, found)
    }

    /**
     * Right before the branch on the check's yes: the answer goes to the extension and its answer
     * takes the register's place, once in the method. A yes still reads the products constant, and a
     * no still goes on to the instruction after its return.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, found: ProductTagIndicator) {
        val code = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.returnType == found.returnType && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.implementation!!.instructions.toList()
        val calls = code.indices.filter { code[it].reference()?.toString() == PRODUCT_TAG_INDICATOR }
        assertEquals("$what: one hook, where the branch was", listOf(found.branchAt), calls)
        val at = calls.single()
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$what: with the answer", listOf(1, found.register), listOf(call.registerCount, call.registerC))
        assertEquals("$what: the check's answer before it", Opcode.MOVE_RESULT, code[at - 1].opcode)
        assertEquals("$what: in the register", found.register, (code[at - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the extension's answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: in the register", found.register, (code[at + 1] as OneRegisterInstruction).registerA)
        val branch = code[at + 2]
        assertEquals("$what: the branch", Opcode.IF_EQZ, branch.opcode)
        assertEquals("$what: on the answer", found.register, (branch as OneRegisterInstruction).registerA)
        assertEquals("$what: a yes reads the products constant", Opcode.SGET_OBJECT, code[at + 3].opcode)
        assertEquals("$what: and answers it", Opcode.RETURN_OBJECT, code[at + 4].opcode)
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        assertEquals("$what: a no goes on past it", addresses[at + 5], addresses[at + 2] + (branch as OffsetInstruction).codeOffset)
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findProductTagIndicator() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private companion object {
        const val INDICATOR = "Lfixture/MediaIndicator;"
        const val OTHER_INDICATOR = "Lfixture/OtherIndicator;"
        const val DECIDER = "Lfixture/IndicatorDecider;"
        const val OTHER_DECIDER = "Lfixture/OtherDecider;"
        const val CHECKER = "Lfixture/ProductCheck;"
        const val CHECKED = "Lfixture/ProductTagsModel;"
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        const val MEDIA_TYPE = "Lcom/instagram/feed/media/Media;"
        const val PUBLIC = 0x11 // public final

        /** A jump put ahead of the method's code: none, onto the branch by if or by switch, or onto the people indicator. */
        enum class Entry { NONE, IF, SWITCH, ELSEWHERE }

        /** Each name's constant, as 450's 385611438 names them. */
        val FIELDS = mapOf("NONE" to "A0D", "PEOPLE" to "A0G", PRODUCTS to "A0I", SHOPPING_ADS to "A0J")

        fun constant(name: String, type: String = INDICATOR) = ImmutableFieldReference(type, FIELDS.getValue(name), type)

        fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<Instruction>) =
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            )

        /** An enum whose static initializer loads each name and keeps its constant. */
        fun enum(type: String, names: List<String>) = ImmutableClassDef(
            type, PUBLIC or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null, null,
            listOf(
                method(
                    type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 2,
                    names.flatMap {
                        listOf(
                            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)),
                            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(type)),
                            ImmutableInstruction21c(Opcode.SPUT_OBJECT, 1, constant(it, type)),
                        )
                    } + ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

        /**
         * Shaped like 450's: the indicator enum, and the method picking a post's indicator, which
         * answers the products constant after a static check's yes and goes on to the people
         * indicator after a no.
         *   0 new-instance   2 invoke-static   5 move-result   6 if-eqz +5   8 sget PRODUCTS   10 return
         *   11 sget PEOPLE   13 return   14 sget NONE   16 return
         */
        fun classes(
            names: List<String> = listOf("NONE", "PEOPLE", PRODUCTS, SHOPPING_ADS),
            secondEnum: Boolean = false,
            secondSite: Boolean = false,
            deciderFlags: Int = PUBLIC,
            parameters: List<String> = listOf(SESSION, MEDIA_TYPE, MEDIA_TYPE),
            checkOpcode: Opcode = Opcode.INVOKE_STATIC,
            branchOffset: Int = 5,
            answerRegister: Int = 0,
            entry: Entry = Entry.NONE,
        ): List<ClassDef> {
            fun read(name: String) = listOf(
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, constant(name)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(CHECKED)),
                ImmutableInstruction35c(checkOpcode, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(CHECKER, "A00", listOf(CHECKED), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, answerRegister),
                ImmutableInstruction21t(Opcode.IF_EQZ, answerRegister, branchOffset),
            ) + read(PRODUCTS) + read("PEOPLE") + read("NONE")
            // Addresses of the code above: the branch at 6, the people indicator at 11. The jump goes in ahead of it.
            val withEntry: List<Instruction> = when (entry) {
                Entry.NONE -> code
                Entry.IF -> listOf<Instruction>(ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2 + 6)) + code
                Entry.ELSEWHERE -> listOf<Instruction>(ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2 + 11)) + code
                Entry.SWITCH -> listOf<Instruction>(ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 3 + 17)) + code +
                    ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(0, 3 + 6)))
            }
            val decider = ImmutableClassDef(
                DECIDER, PUBLIC, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(DECIDER, "A06", parameters, INDICATOR, deciderFlags, 21, withEntry)),
            )
            val other = ImmutableClassDef(
                OTHER_DECIDER, PUBLIC, "Ljava/lang/Object;", null, null, null, null,
                listOf(method(OTHER_DECIDER, "A00", emptyList(), INDICATOR, PUBLIC or AccessFlags.STATIC.value, 1, read(PRODUCTS))),
            )
            return listOf(enum(INDICATOR, names), decider) +
                (if (secondEnum) listOf(enum(OTHER_INDICATOR, listOf(PRODUCTS, SHOPPING_ADS))) else emptyList()) +
                (if (secondSite) listOf(other) else emptyList())
        }
    }
}

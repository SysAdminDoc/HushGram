/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.flags.answerFlagReads
import app.morphe.patches.instagram.misc.flags.findFlagReads
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartOnFollowingHookTest {
    /** The hooks the patch writes are in the FollowingFeed the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(FEED_FLAG, SAVED_FEED, LIMIT_PICKER)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun theSavedFeedsReturnIsFound() {
        val context = PatchContexts.of(listOf(savedFeed()))

        val found = context.findSavedFeedReturn()

        assertEquals(SAVED, found.type)
        assertEquals("A01", found.name)
        assertEquals(8, found.returnAt)
        assertEquals(0, found.register)
    }

    /** The saved name goes past the extension on its way out, and both flags' reads answer through it too. */
    @Test
    fun theSavedFeedAndBothFlagsAnswerThroughTheExtension() {
        val context = PatchContexts.of(listOf(savedFeed(), pickerGate()))

        val reads = context.findFlagReads("test", FEED_PICKER_FLAGS)
        context.defaultSavedFeed(context.findSavedFeedReturn())
        context.answerFlagReads(reads, FEED_FLAG)

        assertEquals(FEED_PICKER_FLAGS.toSet(), reads.map { it.flag }.toSet())
        assertDefaultedAndAnswered("stand-in", context.mutableClassDefBy(SAVED).methods.single { it.name == "A01" })
        assertAnsweredAfterEveryRead("picker", context.mutableClassDefBy(PICKER).methods.single())
    }

    /** Without the For you flag's read, the picker would come up without For you, so that's an update the patch hasn't seen. */
    @Test
    fun aPickerFlagNobodyReadsFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed()))
        assertThrows(PatchException::class.java) { context.findFlagReads("test", FEED_PICKER_FLAGS) }
    }

    @Test
    fun noClassKeepingTheSavedFeedFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(key = "last_selected_tab")))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    @Test
    fun twoGettersFailThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(getters = 2)))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    /** A getter that no longer asks the flag is an update the patch hasn't seen. */
    @Test
    fun aGetterWithoutTheFlagFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(flag = FOR_YOU_PICKER_FLAG)))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    @Test
    fun aGetterReturningItUncastFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(cast = false)))
        assertThrows(PatchException::class.java) { context.findSavedFeedReturn() }
    }

    /** The list the picker fills goes through the extension just before it's frozen, branch included. */
    @Test
    fun thePickersFeedsGoThroughTheExtensionBeforeTheyFreeze() {
        val context = PatchContexts.of(listOf(savedFeed(), pickerList()))

        val freeze = context.findPickerFreeze(context.findSavedFeedReturn())
        assertEquals(LIST_PICKER, freeze.type)
        assertEquals(5, freeze.at)
        assertEquals(1, freeze.register)
        context.limitPicker(freeze)

        assertLimited("stand-in", context.mutableClassDefBy(LIST_PICKER).methods.single())
    }

    @Test
    fun twoFreezesBeforeTheReadFailThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(), pickerList(freezes = 2)))
        assertThrows(PatchException::class.java) { context.findPickerFreeze(context.findSavedFeedReturn()) }
    }

    /** A frozen list nothing was added to isn't the picker's feeds. */
    @Test
    fun aListNothingIsAddedToFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(), pickerList(adds = false)))
        assertThrows(PatchException::class.java) { context.findPickerFreeze(context.findSavedFeedReturn()) }
    }

    @Test
    fun noPickerFailsThePatch() {
        val context = PatchContexts.of(listOf(savedFeed(), pickerList(trace = "FeedTitle")))
        assertThrows(PatchException::class.java) { context.findPickerFreeze(context.findSavedFeedReturn()) }
    }

    @Test
    fun theExtensionIsLeftAlone() {
        val own = savedFeed(type = "Lapp/hushgram/extension/instagram/feed/Probe;")
        val context = PatchContexts.of(listOf(own, savedFeed()))

        assertEquals(SAVED, context.findSavedFeedReturn().type)
    }

    /**
     * In each declared build every read of both flags and the saved feed's getter are found, and
     * after the patch each answer and the saved name go through the extension. On 449 that's twelve
     * reads of the remembered feed flag, three of the For you one, and LX/00lX.
     */
    @Test
    fun eachDeclaredBuildStartsHomeOnFollowing() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { it.loadsTheFlag() || it.holds(SAVED_FEED_KEY) }) holders += ImmutableClassDef.of(classDef)
                    }
                }
                val context = PatchContexts.of(holders)

                val reads = context.findFlagReads("test", FEED_PICKER_FLAGS)
                val saved = context.findSavedFeedReturn()
                context.defaultSavedFeed(saved)
                context.answerFlagReads(reads, FEED_FLAG)

                assertEquals("${bundle.name}: remembered feed reads", 12, reads.count { it.flag == REMEMBERED_FEED_FLAG })
                assertEquals("${bundle.name}: For you reads", 3, reads.count { it.flag == FOR_YOU_PICKER_FLAG })
                assertDefaultedAndAnswered(
                    "${bundle.name} ${saved.type}",
                    context.mutableClassDefBy(saved.type).methods.single { it.name == saved.name && it.parameterTypes.isEmpty() },
                )
                for (read in reads) {
                    val method = context.mutableClassDefBy(read.type).methods.single {
                        it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters
                    }
                    assertAnsweredAfterEveryRead("${bundle.name} ${read.type}->${read.name}", method)
                }
                val freeze = context.findPickerFreeze(saved)
                context.limitPicker(freeze)
                assertLimited(
                    "${bundle.name} ${freeze.type}->${freeze.name}",
                    context.mutableClassDefBy(freeze.type).methods.single {
                        it.name == freeze.name && it.parameterTypes.map(CharSequence::toString) == freeze.parameters
                    },
                )
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun Method.loadsTheFlag(): Boolean = implementation?.instructions?.any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in FEED_PICKER_FLAGS
    } == true

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    /** The getter's String cast is followed by the extension call, the answer back in its register and the return; one call in all. */
    private fun assertDefaultedAndAnswered(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SAVED_FEED }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[hook - 1].opcode)
        val register = (code[hook - 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[hook + 2].opcode)
        assertEquals("$what: the returned register", register, (code[hook + 2] as OneRegisterInstruction).registerA)
        assertAnsweredAfterEveryRead(what, method)
    }

    /** After each flag load, its read's move-result is followed by the extension call and a move-result into the same register. */
    private fun assertAnsweredAfterEveryRead(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val loads = code.indices.filter {
            code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral in FEED_PICKER_FLAGS
        }
        assertTrue("$what loads no flag", loads.isNotEmpty())
        for (load in loads) {
            val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
            val register = (code[result] as OneRegisterInstruction).registerA
            val call: Instruction = code[result + 1]
            assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$what: the hook", FEED_FLAG, (call as ReferenceInstruction).reference.toString())
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
            assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * One call to the extension, handed the list the next instruction freezes, and every branch
     * that would have landed on the freeze lands on the call.
     */
    private fun assertLimited(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == LIMIT_PICKER }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        val call = code[hook] as RegisterRangeInstruction
        assertEquals("$what: one register", 1, call.registerCount)
        val freeze = code[hook + 1]
        assertEquals("$what: the freeze after the hook", Opcode.INVOKE_STATIC, freeze.opcode)
        val frozen = (freeze as ReferenceInstruction).reference as MethodReference
        assertEquals("$what: the frozen list", listOf("Ljava/util/List;"), frozen.parameterTypes.map(CharSequence::toString))
        assertEquals("$what: the hook's list", (freeze as FiveRegisterInstruction).registerC, call.startRegister)

        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        for ((at, instruction) in code.withIndex()) {
            if (instruction !is OffsetInstruction) continue
            assertTrue("$what: the branch at $at skips the hook", addresses[at] + instruction.codeOffset != addresses[hook + 1])
        }
    }

    private companion object {
        const val LIST_PICKER = "Lfixture/FeedPicker;"
        const val SAVED = "Lfixture/SavedFeedType;"
        const val PICKER = "Lfixture/FeedTitle;"
        const val CONFIG = "Lfixture/MobileConfig;"
        const val STORE = "Lfixture/Store;"

        /**
         * Shaped like 449's LX/00lX: a constructor naming the preference, and a getter that asks
         * the flag, then returns the stored name cast to a String, or null without the flag.
         */
        fun savedFeed(
            type: String = SAVED,
            key: String = SAVED_FEED_KEY,
            flag: Long = REMEMBERED_FEED_FLAG,
            getters: Int = 1,
            cast: Boolean = true,
        ): ClassDef {
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val constructor = ImmutableMethod(
                type, "<init>", null, "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(
                    2,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key)),
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                    ),
                    null, null,
                ),
            )
            val code = listOfNotNull<Instruction>(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, flag),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 2, 0, 1, 0, 0, ImmutableMethodReference(CONFIG, "BXd", listOf("J"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, if (cast) 9 else 7),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, ImmutableMethodReference(STORE, "read", null, "Ljava/lang/Object;")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                if (cast) ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/String;")) else null,
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
            val methods = (1..getters).map { n ->
                ImmutableMethod(
                    type, "A0$n", null, "Ljava/lang/String;", instance, null, null,
                    ImmutableMethodImplementation(4, code, null, null),
                )
            }
            return ImmutableClassDef(
                type, instance, "Ljava/lang/Object;", null, null, null, null, listOf(constructor) + methods,
            )
        }

        /**
         * Shaped like the picker's case in 449's LX/08rS.A0D: it names its trace, fills a list,
         * branches to where it freezes the list, then reads the saved feed.
         *
         *     0 const-string v0, trace
         *     1 new-instance v1, ArrayList
         *     2 invoke-direct {v1}, ArrayList-><init>()V
         *     3 invoke-virtual {v1, v0}, AbstractCollection->add (or a nop)
         *     4 if-eqz v3, :freeze
         *     5 :freeze invoke-static {v1}, Lists->freeze(List)List
         *     6 move-result-object v2
         *     7 invoke-virtual {v0}, saved getter
         *     8 move-result-object v0
         *     9 return-object v2
         */
        fun pickerList(freezes: Int = 1, adds: Boolean = true, trace: String = FEED_PICKER): ClassDef {
            val list = "Ljava/util/ArrayList;"
            val freeze = ImmutableMethodReference("Lfixture/Lists;", "freeze", listOf("Ljava/util/List;"), "Ljava/util/List;")
            val code = mutableListOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(trace)),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(list)),
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 1, 0, 0, 0, 0, ImmutableMethodReference(list, "<init>", null, "V")),
                if (adds) {
                    ImmutableInstruction35c(
                        Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0,
                        ImmutableMethodReference("Ljava/util/AbstractCollection;", "add", listOf("Ljava/lang/Object;"), "Z"),
                    )
                } else {
                    ImmutableInstruction10x(Opcode.NOP)
                },
                ImmutableInstruction21t(Opcode.IF_EQZ, 3, 2),
            )
            repeat(freezes) {
                code += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, freeze)
                code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2)
            }
            code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(SAVED, "A01", null, "Ljava/lang/String;"))
            code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2)
            val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            return ImmutableClassDef(
                LIST_PICKER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        LIST_PICKER, "A0D", listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/Object;", static,
                        null, null, ImmutableMethodImplementation(4, code, null, null),
                    ),
                ),
            )
        }

        /** Shaped like 449's title setup: the For you flag's read, and a return when it's off. */
        fun pickerGate(): ClassDef {
            val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val code = listOf<Instruction>(
                ImmutableInstruction51l(Opcode.CONST_WIDE, 0, FOR_YOU_PICKER_FLAG),
                ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(CONFIG)),
                ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 3, 2, 0, 1, 0, 0, ImmutableMethodReference(CONFIG, "BXd", listOf("J"), "Z")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            return ImmutableClassDef(
                PICKER, instance, "Ljava/lang/Object;", null, null, null, null,
                listOf(ImmutableMethod(PICKER, "A09", null, "V", instance, null, null, ImmutableMethodImplementation(4, code, null, null))),
            )
        }
    }
}

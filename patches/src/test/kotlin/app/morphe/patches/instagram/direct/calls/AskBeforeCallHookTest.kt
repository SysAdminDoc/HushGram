/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.calls

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask before a call (#35's first action): the chat's call start asks the extension first with its
 * own arguments, the stubs reach Instagram's own call start and the chat screen's context, and
 * anything the patch can't pick out fails it before a change.
 */
class AskBeforeCallHookTest {
    @Test
    fun theHookAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(CALL_CONFIRM).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (method in listOf(
            HOLD_CALL.substringAfter("->"),
            "$START_CALL_STUB(${OBJECT.repeat(4)}Z)V",
            "$CONTEXT_OF_STUB($OBJECT)$OBJECT",
        )) {
            assertTrue("$method is not in the extension: $declared", method in declared)
        }
    }

    @Test
    fun theCallStartAsksFirstAndTheStubsReachInstagram() {
        val context = PatchContexts.of(listOf(starter(), delegate(), ExtensionDex.classDef(CALL_CONFIRM)))
        val before = starter().methods.single { it.name == "start" }.instructions()

        context.askBeforeCall()

        val start = context.mutableClassDefBy(STARTER).methods.single { it.name == "start" }
        assertAsksFirst("stand-in", start)
        assertEquals("the start's own code follows", before.map { it.opcode }, start.instructions().drop(4).map { it.opcode })

        val stubs = context.mutableClassDefBy(CALL_CONFIRM).methods
        val startCall = stubs.single { it.name == START_CALL_STUB }.instructions()
        assertEquals(
            "the call stub calls Instagram's own start",
            "$STARTER->start(${START_PARAMETERS.joinToString("")})V",
            startCall.first { it.opcode == Opcode.INVOKE_VIRTUAL }.referenceText(),
        )
        val contextOf = stubs.single { it.name == CONTEXT_OF_STUB }.instructions()
        assertEquals("the screen", "$STARTER->screen:$SCREEN", contextOf.first { it.opcode == Opcode.IGET_OBJECT }.referenceText())
        assertEquals("its context", GET_CONTEXT, contextOf.first { it.opcode == Opcode.INVOKE_VIRTUAL }.referenceText())
    }

    @Test
    fun aStarterWithoutTheCallDelegateFailsThePatch() =
        refuses("keeps no call delegate") { PatchContexts.of(listOf(starter(), ExtensionDex.classDef(CALL_CONFIRM))).findChatCall() }

    @Test
    fun twoCallStartsFailThePatch() = refuses("expected exactly one chat call start") {
        PatchContexts.of(listOf(starter(), starter("Lfixture/OtherStarter;"), delegate(), ExtensionDex.classDef(CALL_CONFIRM))).findChatCall()
    }

    /** A static method of the shape, or one taking other arguments, isn't the call start. */
    @Test
    fun aMethodOfAnotherShapeFailsThePatch() {
        refuses("found none") { PatchContexts.of(listOf(starter(static = true), delegate(), ExtensionDex.classDef(CALL_CONFIRM))).findChatCall() }
        refuses("found none") {
            PatchContexts.of(listOf(starter(video = "I"), delegate(), ExtensionDex.classDef(CALL_CONFIRM))).findChatCall()
        }
    }

    /** Without the chat screen's read, the question has nowhere to show. */
    @Test
    fun aStartThatReadsNoChatScreenFailsThePatch() = refuses("expected one chat screen") {
        PatchContexts.of(listOf(starter(readsScreen = false), delegate(), ExtensionDex.classDef(CALL_CONFIRM))).findChatCall()
    }

    /** In each declared build: the one chat call start, asking the extension first, and both stubs filled. */
    @Test
    fun eachDeclaredBuildAsksBeforeACall() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val kept = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.methods.any { method -> method.parameterTypes.any { it.toString() == CO_WATCH } }) {
                            kept += ImmutableClassDef.of(classDef)
                        }
                    }
                }
                kept += FixtureDex.classesHolding(bundle, CALL_DELEGATE_TEXT).map { ImmutableClassDef.of(it) }
                val context = PatchContexts.of(kept.distinctBy { it.type } + ExtensionDex.classDef(CALL_CONFIRM))

                val found = context.findChatCall()
                context.askBeforeCall()

                val start = context.mutableClassDefBy(found.start.definingClass).methods.single {
                    it.name == found.start.name && it.parameterTypes.map(CharSequence::toString) == found.start.parameterTypes.map(CharSequence::toString)
                }
                assertAsksFirst("${bundle.name} ${start.definingClass}->${start.name}", start)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refuses(reason: String, patch: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** The hook with this and the four arguments, its answer in v0, the early return, and a branch past it to Instagram's code. */
    private fun assertAsksFirst(what: String, method: Method) {
        val code = method.instructions()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the hook", HOLD_CALL, code[0].referenceText())
        assertEquals("$what: this first", method.implementation!!.registerCount - 5, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: this and four arguments", 5, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the answer's register", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the branch's target", 4, (method.implementation!!.instructions.toList()[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the early return", Opcode.RETURN_VOID, code[3].opcode)
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == HOLD_CALL })
    }

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STARTER = "Lfixture/CallStarter;"
        const val SCREEN = "Lfixture/ChatScreen;"
        const val DELEGATE = "Lfixture/CallDelegate;"
        val START_PARAMETERS = listOf("Lfixture/Chat;", "Lfixture/Entry;", CO_WATCH, "Z")

        /** Shaped like 450's X.01YY: the delegate and screen fields, and a start that asks the screen for its context. */
        fun starter(type: String = STARTER, static: Boolean = false, video: String = "Z", readsScreen: Boolean = true): ClassDef {
            val self = if (static) 0 else 1
            val registers = 1 + self + 4
            val code = mutableListOf<Instruction>()
            if (readsScreen) {
                code += ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 1, ImmutableFieldReference(type, "screen", SCREEN))
                code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(FRAGMENT, "getContext", emptyList(), "Landroid/content/Context;"))
            }
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
                listOf(
                    ImmutableField(type, "screen", SCREEN, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
                    ImmutableField(type, "delegate", DELEGATE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
                ),
                listOf(
                    ImmutableMethod(
                        type, "start", START_PARAMETERS.dropLast(1).plus(video).map { ImmutableMethodParameter(it, null, null) }, "V",
                        access, null, null, ImmutableMethodImplementation(registers, code, null, null),
                    ),
                ),
            )
        }

        /** The chat's call delegate, holding the text it logs. */
        fun delegate(): ClassDef = ImmutableClassDef(
            DELEGATE, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    DELEGATE, "startCall", listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), "V",
                    AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(
                        2,
                        listOf(
                            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(CALL_DELEGATE_TEXT)),
                            ImmutableInstruction10x(Opcode.RETURN_VOID),
                        ),
                        null, null,
                    ),
                ),
            ),
        )
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.glass

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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bottom padding Instagram gives a chat's messages goes through the extension as it's set, and as the chat
 * reads it back, so the room the glass gives them under the message bar stays (#528).
 */
class ChatListPaddingTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(GLASS_TOP_BAR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SET_BOTTOM_PADDING is not in the extension: $declared", SET_BOTTOM_PADDING in declared)
        assertTrue("$READ_BOTTOM_PADDING is not in the extension: $declared", READ_BOTTOM_PADDING in declared)
    }

    @Test
    fun everyDeclaredBundleHooksItsChatPadding() {
        var checked = 0
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun everyOtherBuildHooksItsChatPadding() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    /** Sites found but no hooks in the extension refuses under the patch's name, and no sites changes nothing. */
    @Test
    fun withoutTheHooksItRefuses() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(setterClass(), chat())).keepChatPadding() }
        assertTrue(failure.message, failure.message!!.startsWith("Glass in chats"))
        val none = PatchContexts.of(listOf(setterClass(), ImmutableClassDef.of(ExtensionDex.classDef(GLASS_TOP_BAR))))
        assertNull(none.findChatPadding())
        none.keepChatPadding()
    }

    /**
     * The helper's bottom goes through the hook right before its setPadding call, and the chat's read of the list's
     * padding right after its move-result. A read something jumps past isn't hooked.
     */
    @Test
    fun theHelperAndTheReadGoThroughTheHooks() {
        val context = PatchContexts.of(listOf(setterClass(), chat(), ImmutableClassDef.of(ExtensionDex.classDef(GLASS_TOP_BAR))))
        val sites = context.findChatPadding()
        assertNotNull(sites)
        assertEquals(listOf("bottom", 6, 3, 4), sites!!.setter.let { listOf(it.method.name, it.call, it.view, it.bottom) })
        assertEquals(listOf("pad", 2, 0, 1), sites.read.let { listOf(it.method.name, it.result, it.list, it.value) })

        context.keepChatPadding()

        val setter = context.mutableClassDefBy(PAD).methods.single().code()
        assertEquals("the hook before setPadding", listOf(6), setter.indices.filter { setter[it].named(SET_BOTTOM_PADDING) })
        assertEquals("the view and the padding", listOf(3, 2), (setter[6] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("its answer is the padding", listOf(Opcode.MOVE_RESULT, 4), listOf(setter[7].opcode, (setter[7] as OneRegisterInstruction).registerA))
        assertEquals("setPadding takes it", 4, (setter[8] as FiveRegisterInstruction).registerG)

        val methods = context.mutableClassDefBy(CHAT).methods.associateBy { it.name }
        val pad = methods.getValue("pad").code()
        assertEquals("the hook after the read", listOf(3), pad.indices.filter { pad[it].named(READ_BOTTOM_PADDING) })
        assertEquals("the list and the read", listOf(0, 1), (pad[3] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
        assertEquals("its answer is the read", listOf(Opcode.MOVE_RESULT, 1), listOf(pad[4].opcode, (pad[4] as OneRegisterInstruction).registerA))
        assertTrue("jumped past: no hook", methods.getValue("jumped").code().none { it.named(READ_BOTTOM_PADDING) })
    }

    /**
     * The one helper both of a chat's padding callers hand the message list to gets its hook right before its
     * setPadding, and the one read of the list's padding gets its hook right after, each with Instagram's code
     * around it kept.
     */
    private fun hooks(bundle: File, label: String) {
        val context = PatchContexts.of(paddingClasses(bundle) + ImmutableClassDef.of(ExtensionDex.classDef(GLASS_TOP_BAR)))
        val sites = context.findChatPadding()
        assertNotNull("$label: the chat's padding", sites)
        val setter = sites!!.setter
        val read = sites.read
        val setterBefore = setter.method.code()
        val readBefore = read.method.code()
        val setterRef = "${setter.method.definingClass}->${setter.method.name}(Landroid/view/View;I)V"
        assertTrue("$label: the read's method calls the helper", readBefore.any { it.named(setterRef) })
        assertEquals("$label: the read is the list's padding", "Landroid/view/View;->getPaddingBottom()I", (readBefore[read.result - 1] as ReferenceInstruction).reference.toString())

        context.keepChatPadding()

        val setterAfter = context.mutate(setter.method).code()
        assertEquals("$label: one hook, before setPadding", listOf(setter.call), setterAfter.indices.filter { setterAfter[it].named(SET_BOTTOM_PADDING) })
        assertEquals("$label: the view and the padding", listOf(setter.view, 2), (setterAfter[setter.call] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$label: setPadding takes the answer", setter.bottom, (setterAfter[setter.call + 2] as FiveRegisterInstruction).registerG)
        assertEquals("$label: the helper's own code around it", setterBefore.kinds(), setterAfter.filterIndexed { at, _ -> at != setter.call && at != setter.call + 1 }.kinds())

        val readAfter = context.mutate(read.method).code()
        assertEquals("$label: one hook, after the read", listOf(read.result + 1), readAfter.indices.filter { readAfter[it].named(READ_BOTTOM_PADDING) })
        assertEquals("$label: its answer is the read", read.value, (readAfter[read.result + 2] as OneRegisterInstruction).registerA)
        assertEquals("$label: the chat's own code around it", readBefore.kinds(), readAfter.filterIndexed { at, _ -> at != read.result + 1 && at != read.result + 2 }.kinds())
    }

    /** The bundle's classes reading a message list from a field, and those with a static (View, int) method calling setPadding. */
    private fun paddingClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val readsList = classDef.methods.any { method ->
                    method.code().any { it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == MESSAGE_LIST }
                }
                val sets = classDef.methods.any { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map(Any::toString) == listOf("Landroid/view/View;", "I") &&
                        method.code().any { it.named("Landroid/view/View;->setPadding(IIII)V") }
                }
                if (readsList || sets) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    private fun BytecodePatchContext.mutate(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

    /**
     * Instagram's helper: `bottom(View, int)`, five registers, the view in v3 and the padding in v4, keeping the
     * left, top and right padding: three reads with their move-results, then `setPadding {v3, v2, v1, v0, v4}`.
     */
    private fun setterClass(): ClassDef {
        val code = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, viewCall("getPaddingLeft", listOf(), "I")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, viewCall("getPaddingTop", listOf(), "I")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 3, 0, 0, 0, 0, viewCall("getPaddingRight", listOf(), "I")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 5, 3, 2, 1, 0, 4, viewCall("setPadding", listOf("I", "I", "I", "I"), "V")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val method = ImmutableMethod(
            PAD, "bottom", listOf("Landroid/view/View;", "I").map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(5, code, null, null),
        )
        return ImmutableClassDef(PAD, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method))
    }

    /**
     * A chat: pad reads the list from its field into v0, reads its bottom padding into v1, pads it through the
     * helper and keeps the newest message in view, `iget-object v0, p0, list; getPaddingBottom {v0}; move-result v1;
     * bottom {v0, p1}; newest {v0, v1}; return-void`; jumped branches past the read onto the call after it.
     */
    private fun chat(): ClassDef {
        val list = ImmutableFieldReference(CHAT, "list", MESSAGE_LIST)
        val paddingBottom = viewCall("getPaddingBottom", listOf(), "I")
        val pad = listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, list),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, paddingBottom),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 4, 0, 0, 0, BOTTOM),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, NEWEST),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        // iget-object (2 units) then the branch at address 2, over the read (3) and its move-result (1) to address 8.
        val jumped = listOf(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, list),
            ImmutableInstruction21t(Opcode.IF_EQZ, 4, 6),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, paddingBottom),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 4, 0, 0, 0, BOTTOM),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, NEWEST),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val methods = listOf(pad to "pad", jumped to "jumped").map { (code, name) ->
            ImmutableMethod(
                CHAT, name, listOf(ImmutableMethodParameter("I", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(5, code, null, null),
            )
        }
        return ImmutableClassDef(CHAT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    }

    private fun viewCall(name: String, parameters: List<String>, returnType: String) =
        ImmutableMethodReference("Landroid/view/View;", name, parameters, returnType)

    private fun Instruction.named(reference: String) = (this as? ReferenceInstruction)?.reference?.toString() == reference

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** The opcodes, payload alignment nops left out and widened gotos read as gotos: an insert can change either. */
    private fun List<Instruction>.kinds() = filter { it.opcode != Opcode.NOP }.map {
        when (it.opcode) {
            Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
            else -> it.opcode
        }
    }

    private companion object {
        const val PAD = "Lfixture/Pad;"
        const val CHAT = "Lfixture/Chat;"
        val BOTTOM = ImmutableMethodReference(PAD, "bottom", listOf("Landroid/view/View;", "I"), "V")
        val NEWEST = ImmutableMethodReference("Lfixture/Keep;", "newest", listOf("Landroidx/recyclerview/widget/RecyclerView;", "I"), "V")
    }
}

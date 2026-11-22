/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** An opened chat's background layers go through the extension before they reach the view (#528). */
class ChatBackgroundTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ACCENT_THEME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CHAT_BACKGROUND is not in the extension: $declared", CHAT_BACKGROUND in declared)
        assertTrue("$CHAT_HEADER is not in the extension: $declared", CHAT_HEADER in declared)
    }

    @Test
    fun everyDeclaredBundleHooksItsChatBackground() {
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
    fun everyOtherBuildHooksItsChatBackground() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    /** The site found but no hook in the extension refuses under the patch's name, and no site changes nothing. */
    @Test
    fun withoutTheHookItRefuses() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(chat())).paintChatBackground() }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
        val none = PatchContexts.of(listOf(ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        assertNull(none.findChatBackground())
        none.paintChatBackground()
    }

    /**
     * The hook takes the layers right before the call handing them on, and a branch onto that call lands
     * on the hook, so every way there goes through it. A method writing over the layers first, or one
     * building none, isn't the chat's.
     */
    @Test
    fun everyWayOntoTheCallGoesThroughTheHook() {
        val context = PatchContexts.of(listOf(chat(), ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        val site = context.findChatBackground()
        assertNotNull(site)
        assertEquals(listOf("paint", 4, 1), listOf(site!!.method.name, site.call, site.layers))

        context.paintChatBackground()

        val code = context.mutableClassDefBy(CHAT).methods.single { it.name == "paint" }.code()
        val hook = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == CHAT_BACKGROUND }
        assertEquals("the hook before the call", 4, hook)
        assertEquals("the layers", listOf(1, 1), (code[hook] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("the call after it", APPLY.toString(), (code[hook + 1] as ReferenceInstruction).reference.toString())
        assertEquals("the branch lands on the hook", hook, target(code, 2))
        for (name in listOf("moved", "plain")) {
            assertTrue("$name: no hook", context.mutableClassDefBy(CHAT).methods.single { it.name == name }.code().none { it.opcode == Opcode.INVOKE_STATIC_RANGE })
        }
    }

    /**
     * The one method loading a thread background id that builds layers and hands them on gets the hook
     * right before that call, with the layers it built, and keeps Instagram's code around it.
     */
    private fun hooks(bundle: File, label: String) {
        val classes = chatClasses(bundle)
        val context = PatchContexts.of(classes + ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME)))
        val site = context.findChatBackground()
        assertNotNull("$label: the chat background", site)
        val before = site!!.method.code()
        val built = before.indexOfFirst { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == "Landroid/graphics/drawable/LayerDrawable;" }
        assertEquals("$label: the layers the method built", site.layers, (before[built] as OneRegisterInstruction).registerA)
        assertTrue("$label: built before the call", built in 0 until site.call)
        val call = before[site.call]

        context.paintChatBackground()

        val code = context.mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
        }.code()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == CHAT_BACKGROUND }
        assertEquals("$label: one hook, before the call", listOf(site.call), hooks)
        assertEquals("$label: the layers", listOf(site.layers, 1), (code[site.call] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        val after = code[site.call + 1]
        assertEquals("$label: the call", (call as ReferenceInstruction).reference.toString(), (after as ReferenceInstruction).reference.toString())
        assertEquals("$label: the call takes the layers", site.layers, (after as FiveRegisterInstruction).registerC)
        // A payload's alignment nop can come or go with the insert.
        val kept = code.filterIndexed { index, it -> index != site.call && it.opcode != Opcode.NOP }
        assertEquals("$label: Instagram's own code around it", before.filter { it.opcode != Opcode.NOP }.map { kind(it.opcode) }, kept.map { kind(it.opcode) })
        hooksHeader(context, label)
    }

    /** The bundle's classes loading an id of [THREAD_BACKGROUND_IDS] or the header's trace key. */
    private fun chatClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { method -> method.code().any { it.loadsId() } }) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    private fun Instruction.loadsId(): Boolean =
        this is NarrowLiteralInstruction && (narrowLiteral.toLong() and 0xffffffffL).let { it in THREAD_BACKGROUND_IDS || it == CHAT_HEADER_TRACE }

    /**
     * The one method loading the header's trace key gets the hook right after the call coloring the header,
     * with the header that call took, and keeps Instagram's code around it.
     */
    private fun hooksHeader(context: BytecodePatchContext, label: String) {
        val site = context.findChatHeader()
        assertNotNull("$label: the chat header", site)
        val before = site!!.method.code()
        val call = before[site.call] as FiveRegisterInstruction
        assertEquals("$label: the header the call colors", site.header, call.registerC)
        assertEquals("$label: the trace key", CHAT_HEADER_TRACE, before.take(site.call).last { it.loadsId() }.let { (it as NarrowLiteralInstruction).narrowLiteral.toLong() and 0xffffffffL })

        context.paintChatHeader()

        val code = context.mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
        }.code()
        val hooks = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == CHAT_HEADER }
        assertEquals("$label: one hook, after the call", listOf(site.call + 1), hooks)
        assertEquals("$label: the header", listOf(site.header, 1), (code[site.call + 1] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
        assertEquals("$label: the call before it", (call as ReferenceInstruction).reference.toString(), (code[site.call] as ReferenceInstruction).reference.toString())
        val kept = code.filterIndexed { index, it -> index != site.call + 1 && it.opcode != Opcode.NOP }
        assertEquals("$label: Instagram's own code around it", before.filter { it.opcode != Opcode.NOP }.map { kind(it.opcode) }, kept.map { kind(it.opcode) })
    }

    /**
     * A chat class: paint loads the id, builds layers into v1, branches onto the call handing them to the
     * view, `const v0, id; new-instance v1; if-eqz v0, call; const/4 v0, 0; invoke-static {v1, p0, v0}; return-void`;
     * moved writes over the layers before the call, and plain builds none.
     */
    private fun chat(): ClassDef {
        val id = THREAD_BACKGROUND_IDS.first().toInt()
        val layers = ImmutableTypeReference("Landroid/graphics/drawable/LayerDrawable;")
        val paint = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, layers),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 3),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 3, 0, 0, 0, APPLY),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val moved = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, layers),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 3, 0, 0, 0, APPLY),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val plain = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, id),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 3, 0, 0, 0, APPLY),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val methods = listOf(method("paint", paint), method("moved", moved), method("plain", plain))
        return ImmutableClassDef(CHAT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    }

    private fun method(name: String, code: List<Instruction>) = ImmutableMethod(
        CHAT, name, listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(4, code, null, null),
    )

    /** The index of the instruction the branch at [at] goes to. */
    private fun target(code: List<Instruction>, at: Int): Int {
        val offsets = code.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
        return offsets.indexOf(offsets[at] + (code[at] as OffsetInstruction).codeOffset)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun kind(opcode: Opcode) = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    /**
     * The header's hook follows the call coloring it, with the header that call took. A method where
     * something jumps onto the instruction after the call isn't hooked, and without the hook in the
     * extension it refuses under the patch's name.
     */
    @Test
    fun theHeaderHookFollowsTheCallUnlessSomethingJumpsPastIt() {
        val context = PatchContexts.of(listOf(header(), ImmutableClassDef.of(ExtensionDex.classDef(ACCENT_THEME))))
        val site = context.findChatHeader()
        assertNotNull(site)
        assertEquals(listOf("color", 1, 1), listOf(site!!.method.name, site.call, site.header))

        context.paintChatHeader()

        val methods = context.mutableClassDefBy(HEADER).methods.associateBy { it.name }
        val code = methods.getValue("color").code()
        assertEquals("the hook after the call", listOf(2), code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == CHAT_HEADER })
        assertEquals("the header", 1, (code[2] as RegisterRangeInstruction).startRegister)
        assertTrue("jumped onto: no hook", methods.getValue("jumped").code().none { it.opcode == Opcode.INVOKE_STATIC_RANGE })
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(header())).paintChatHeader() }
        assertTrue(failure.message, failure.message!!.startsWith("Accent color"))
    }

    /**
     * A header class: color loads the trace key and colors the header, `const v0, key; invoke-static {p0, p1, v0}; return-void`,
     * and jumped branches past the call onto the return after it.
     */
    private fun header(): ClassDef {
        val key = CHAT_HEADER_TRACE.toInt()
        val color = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, key),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 2, 0, 0, 0, COLORS),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val jumped = listOf(
            ImmutableInstruction31i(Opcode.CONST, 0, key),
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 5),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 2, 0, 0, 0, COLORS),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val methods = listOf(color to "color", jumped to "jumped").map { (code, name) ->
            ImmutableMethod(
                HEADER, name, listOf("Landroid/view/View;", "I").map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(3, code, null, null),
            )
        }
        return ImmutableClassDef(HEADER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
    }

    private companion object {
        const val CHAT = "Lfixture/Chat;"
        const val HEADER = "Lfixture/Header;"
        val APPLY = ImmutableMethodReference(
            "Lfixture/Apply;", "set", listOf("Landroid/graphics/drawable/Drawable;", "Landroid/view/View;", "I"), "V",
        )
        val COLORS = ImmutableMethodReference("Lfixture/Apply;", "color", listOf("Landroid/view/View;", "I", "I"), "V")
    }
}

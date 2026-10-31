/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.viewonce

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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mark one-time photos and videos (#165): the old photo and video bubble's bind hands the
 * bubble's frame and the message's view once flag to the extension, in every 453 build. The hook
 * only draws, so these tests also hold the patch to the one hook and to nothing that saves,
 * replays, marks seen or calls out.
 */
class MarkViewOnceHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(VIEW_ONCE_MARK).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("mark is not in the extension: $declared", VIEW_ONCE_MARK_HOOK.substringAfter("->") in declared)
        assertTrue("the hook takes no boolean", "Z" !in VIEW_ONCE_MARK_HOOK.substringAfter("(").substringBefore(")"))
    }

    /** The bind's class and pools, the holder and message classes it casts to, and what makes the message. */
    private fun slice(bundle: File, withMakers: Boolean = true): List<ClassDef> {
        val bind = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, VIEW_ONCE_BIND_KEYS[0]))
        val casts = bind.flatMap { it.methods }.filter { it.isBind() }.flatMap { method ->
            method.implementation!!.instructions.filter { it.opcode == Opcode.CHECK_CAST }.take(2)
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
        }.toSet()
        val types = FixtureDex.classes(bundle, casts).values.toList()
        val makers = if (withMakers) {
            FixtureDex.withStringPools(bundle, FixtureDex.classesCreating(bundle, casts))
        } else {
            emptyList()
        }
        return (bind + types + makers + ExtensionDex.classDef(VIEW_ONCE_MARK)).distinctBy { it.type }
    }

    private fun Method.isBind() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.size == 2 && parameterTypes.all { it.startsWith("L") }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
    }

    /** In every 453 build: one hook, after the casts, reading the message's flag and the holder's frame. */
    @Test
    fun everyBuildMarksTheBubbleOnce() {
        var checked = 0
        for (bundle in bundles()) {
            val context = PatchContexts.of(slice(bundle))
            val bind = context.findViewOnceBind()
            val before = bind.method.implementation!!.instructions.count { it.opcode != Opcode.NOP }
            bind.markBubble()
            val after = context.mutableClassDefBy(bind.method.definingClass).methods.single {
                it.name == bind.method.name && it.parameterTypes == bind.method.parameterTypes
            }.implementation!!.instructions.toList()
            assertEquals("${bundle.name}: three instructions added", before + 3, after.count { it.opcode != Opcode.NOP })
            val hooks = after.indices.filter { index ->
                after[index].opcode == Opcode.INVOKE_STATIC &&
                    ((after[index] as ReferenceInstruction).reference as MethodReference).let {
                        it.definingClass == VIEW_ONCE_MARK && it.name == "mark"
                    }
            }
            assertEquals("${bundle.name}: one hook", 1, hooks.size)
            val at = hooks.single()
            assertEquals("${bundle.name}: the flag read", Opcode.IGET_BOOLEAN, after[at - 2].opcode)
            assertEquals("${bundle.name}: the frame read", Opcode.IGET_OBJECT, after[at - 1].opcode)
            assertEquals("${bundle.name}: the flag is a boolean", "Z", bind.flag.type)
            assertEquals("${bundle.name}: the frame", "Landroid/widget/FrameLayout;", bind.frame.type)
            assertEquals("${bundle.name}: the flag is read from the message", bind.flag, (after[at - 2] as ReferenceInstruction).reference)
            val calls = after.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .filter { it.definingClass == VIEW_ONCE_MARK }
            assertTrue("${bundle.name}: only the one hook is added", calls.size == 1)
            checked++
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun aBubbleItCantTellApartFailsThePatch() {
        val bundle = bundles().first()
        val full = slice(bundle)

        val none = full.filter { classDef -> classDef.methods.none { it.isBind() } || classDef.type == VIEW_ONCE_MARK }
        refuses("found none", none)

        val noMakers = slice(bundle, withMakers = false)
        refuses("nothing that makes", noMakers)

        val bind = PatchContexts.of(full).findViewOnceBind()
        val holder = bind.frame.definingClass
        val bare = full.map { classDef ->
            if (classDef.type != holder) classDef else ImmutableClassDef(
                classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile,
                classDef.annotations, classDef.fields.filter { it.type != "Landroid/widget/FrameLayout;" },
                classDef.methods,
            )
        }
        refuses("FrameLayout fields, not one", bare)
    }

    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context: BytecodePatchContext = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findViewOnceBind() }
        assertNotNull(refusal.message)
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** The patch adds no call to a save, a replay, a seen receipt or the network. */
    @Test
    fun theExtensionDrawsAndNothingElse() {
        val calls = ExtensionDex.classDef(VIEW_ONCE_MARK).methods
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference }
            .filterIsInstance<MethodReference>().map { it.definingClass }.toSet()
        for (forbidden in listOf("Ljava/net/", "Landroid/net/", "Ljava/io/File", "Landroid/content/ContentResolver;",
            "Landroid/provider/MediaStore")) {
            assertTrue("calls $forbidden", calls.none { it.startsWith(forbidden) })
        }
        val fields = ExtensionDex.classDef(VIEW_ONCE_MARK).methods
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference }.filterIsInstance<FieldReference>()
        assertTrue("writes no field of Instagram", fields.none { it.definingClass.startsWith("LX/") })
    }
}

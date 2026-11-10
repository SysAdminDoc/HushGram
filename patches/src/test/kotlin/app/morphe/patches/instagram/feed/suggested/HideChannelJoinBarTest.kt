/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the Join bar on channel posts (#625): in every build the bar's binder runs its parameters
 * through ChannelJoinBar.details first thing, casts the answer back and keeps the rest of its code,
 * and ChannelJoinBar.row reads the bar's row from its holder.
 */
class HideChannelJoinBarTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(CHANNEL_JOIN_BAR).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$CHANNEL_JOIN_BAR->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CHANNEL_JOIN_BAR_DETAILS is not in the extension: $declared", CHANNEL_JOIN_BAR_DETAILS in declared)
        val row = "$CHANNEL_JOIN_BAR->$CHANNEL_JOIN_BAR_ROW_STUB(Ljava/lang/Object;)Landroid/view/View;"
        assertTrue("$row is not in the extension: $declared", row in declared)
    }

    @Test
    fun eachDeclaredBuildHidesTheBar() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hidesTheBar(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHidesTheBar() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hidesTheBar(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /** Without the bar's class, nothing else has its shape, and the find stops. */
    @Test
    fun withoutTheBarTheFindStops() {
        val version = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.first()
        val bundle = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.first()
        val classes = barClasses(bundle) + ExtensionDex.classDef(CHANNEL_JOIN_BAR)
        val owner = PatchContexts.of(classes).findHideChannelJoinBar().owner
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(classes.filter { it.type != owner }).findHideChannelJoinBar()
        }
        assertTrue(failure.message!!, failure.message!!.contains("found 0"))
    }

    /**
     * Every class calling View.addOnAttachStateChangeListener, which the bar's binder does, and the
     * classes they make, among them the bar's view holder, whose row the extension reads.
     */
    private fun barClasses(bundle: File): List<ClassDef> {
        val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.stringSection.any { it == ATTACH } }) { method ->
            method.code().any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == ATTACH }
        }.map { it.definingClass }.toSet()
        val bars = FixtureDex.classes(bundle, callers).values
        val made = bars.flatMap { it.methods }.flatMap { it.code() }.filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet() - callers
        return bars + FixtureDex.classes(bundle, made).values
    }

    /**
     * The binder starts with the range call on its holder, post and details, the answer moved back
     * into the details' register and cast to their type, and its own code follows unchanged. The row
     * stub reads the holder's view that the binder's no details path sets gone.
     */
    private fun hidesTheBar(bundle: File, label: String) {
        val context = PatchContexts.of(barClasses(bundle) + ExtensionDex.classDef(CHANNEL_JOIN_BAR))
        val found = context.findHideChannelJoinBar()
        val bind = context.mutableClassDefBy(found.owner).methods.single {
            it.name == found.binder && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        val stock = bind.code()
        found.write(context)

        val code = bind.code()
        val details = bind.implementation!!.registerCount - 1
        val call = code[0] as RegisterRangeInstruction
        assertEquals("$label: the parameters go to the extension first", Opcode.INVOKE_STATIC_RANGE to CHANNEL_JOIN_BAR_DETAILS, code[0].opcode to code[0].referenceText())
        assertEquals("$label: holder, post and details", (details - 2) to 3, call.startRegister to call.registerCount)
        assertEquals("$label: and come back into the details' register", Opcode.MOVE_RESULT_OBJECT to details, code[1].opcode to (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$label: cast to the details' type", Opcode.CHECK_CAST to found.parameters.last(), code[2].opcode to code[2].referenceText())
        assertEquals("$label: one hook", 1, code.count { it.referenceText() == CHANNEL_JOIN_BAR_DETAILS })
        assertEquals("$label: the binder's own code is kept", stock.shape(), code.drop(3).shape())

        val branch = stock.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        assertEquals("$label: the row is the view the no details path sets gone", found.row, stock[branch + 1].referenceText())
        assertTrue("$label: a view of the holder", found.row.startsWith("${found.parameters.first()}->") && found.row.endsWith(":Landroid/view/View;"))
        val stub = context.mutableClassDefBy(CHANNEL_JOIN_BAR).methods.single { it.name == CHANNEL_JOIN_BAR_ROW_STUB }.code()
        assertEquals(
            "$label: the row stub reads it from the holder",
            listOf(Opcode.CHECK_CAST to found.parameters.first(), Opcode.IGET_OBJECT to found.row, Opcode.RETURN_OBJECT to null),
            stub.take(3).map { it.opcode to it.referenceText() },
        )
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** Opcodes and references without padding, with a widened goto read as a goto. */
    private fun List<Instruction>.shape() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { kind(it.opcode) to it.referenceText() }

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    private companion object {
        const val ATTACH = "addOnAttachStateChangeListener"
    }
}

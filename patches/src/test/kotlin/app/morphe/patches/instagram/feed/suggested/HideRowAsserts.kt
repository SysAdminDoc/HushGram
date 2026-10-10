/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.download.reel.OPTION
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** What Hide posts from this account leaves in a patched build, shared by the tests of every patch that can share its menu. */
object HideRowAsserts {
    /** What the row leaves in [context] once written, whichever other patches also wrote there. */
    fun assertRowWritten(context: BytecodePatchContext, label: String) {
        val all = mutableListOf<Method>()
        context.classDefForEach { c -> all += c.methods }
        fun calls(ref: String) = all.filter { m -> m.code().any { it.referenceText() == ref } }
        assertEquals("$label: one builder offers the row", 1, calls(OFFER_HIDE).size)
        assertEquals("$label: the builder offers it once", 1, calls(OFFER_HIDE).single().code().count { it.referenceText() == OFFER_HIDE })
        val tap = calls(HIDE_TAPPED).single()
        assertEquals("$label: one tap", 1, tap.code().count { it.referenceText() == HIDE_TAPPED })
        assertEquals("$label: the handler asks for the option once", 1, tap.code().count { it.referenceText() == HIDE_OPTION })
        assertEquals("$label: the handler is a menu handler", listOf(OPTION), tap.parameterTypes.map(Any::toString))
        val list = calls(ALLOW_HIDE).single()
        val returns = list.code().count { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: the short list asks before each return", returns, list.code().count { it.referenceText() == ALLOW_HIDE })

        val row = context.classDefBy(HIDE_ROW)
        val media = row.methods.single { it.name == "menuMedia" }.code()
        assertEquals("$label: menuMedia reads the post off the state", Opcode.CHECK_CAST, media[0].opcode)
        assertEquals(Opcode.IGET_OBJECT, media[1].opcode)
        val add = row.methods.single { it.name == "addRow" }.code()
        assertTrue("$label: addRow calls the builder's adder", add.any { it.opcode == Opcode.INVOKE_STATIC_RANGE })
        val made = row.methods.single { it.name == "newOption" }.code()
        assertEquals(
            "$label: direct native construction", "$OPTION-><init>(Ljava/lang/String;II)V",
            made.single { it.opcode == Opcode.INVOKE_DIRECT }.referenceText(),
        )
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

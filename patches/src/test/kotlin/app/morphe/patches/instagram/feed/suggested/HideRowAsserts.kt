/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.OPTION
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** What Hide posts from this account leaves in a patched build, shared by the tests of every patch that can share its menu. */
object HideRowAsserts {
    private val ADDS = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE)

    /**
     * Adds to [classes], a slice of [bundle] holding the feed menu's builder, what the builder reads
     * and makes its rows of, which the patches' own rows copy: the lambdas the methods loading
     * Download take, the row adder's class and the classes it takes, the classes of the kinds handed
     * to it, and the row, the fields and the statics the adder writes.
     */
    fun loadMenuClasses(bundle: File, classes: MutableList<ClassDef>) {
        fun load(types: Collection<String>) {
            val missing = types.filter { type -> type.startsWith("L") && classes.none { it.type == type } }.toSet()
            if (missing.isNotEmpty()) classes += FixtureDex.classes(bundle, missing).values
        }
        val loaders = classes.flatMap { it.methods }.filter { method -> method.code().any { it.referenceText() == DOWNLOAD } }
        val adders = loaders.flatMap { it.code() }.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.filter { call ->
            val types = call.parameterTypes.map(Any::toString)
            call.returnType == "V" && OPTION in types && "Ljava/lang/CharSequence;" in types && "Ljava/util/ArrayList;" in types &&
                call.definingClass in types
        }
        load(loaders.flatMap { it.parameterTypes.map(Any::toString) } + adders.flatMap { listOf(it.definingClass) + it.parameterTypes.map(Any::toString) })
        val taken = adders.flatMap { it.parameterTypes.map(Any::toString) }.toSet()
        load(loaders.flatMap { it.code() }.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .filter { it.type in taken }.map { it.definingClass })
        val bodies = adders.mapNotNull { call ->
            classes.firstOrNull { it.type == call.definingClass }?.methods?.firstOrNull {
                it.name == call.name && it.returnType == call.returnType &&
                    it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
            }
        }
        load(bodies.flatMap { it.code() }.mapNotNull { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is FieldReference -> reference.definingClass
                is TypeReference -> reference.type
                else -> null
            }
        })
    }

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
        // The menu is the helper, which answers the post with its own getter.
        val media = row.methods.single { it.name == "menuMedia" }.code()
        assertEquals("$label: menuMedia",
            listOf(Opcode.CHECK_CAST, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), media.take(4).map { it.opcode })
        assertTrue("$label: menuMedia asks the helper", media[1].referenceText()!!.startsWith(media[0].referenceText() + "->"))
        // The row is made the way the builder's adder makes it, and put on the list.
        val add = row.methods.single { it.name == "addRow" }.code()
        assertEquals("$label: addRow makes the row", Opcode.NEW_INSTANCE, add.first().opcode)
        assertTrue("$label: addRow adds it", add.any { it.opcode in ADDS && it.referenceText()!!.endsWith("->add(Ljava/lang/Object;)Z") })
        assertTrue("$label: addRow calls nothing static", add.none { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE })
        assertTrue("$label: addRow's option is the one it's handed", add.any { it.opcode == Opcode.CHECK_CAST && it.referenceText() == OPTION })
        val made = row.methods.single { it.name == "newOption" }.code()
        val init = made.single { it.opcode == Opcode.INVOKE_DIRECT }
        assertEquals("$label: direct native construction", "$OPTION-><init>(Ljava/lang/String;II)V", init.referenceText())
        // Download's icon goes through HideAccountRow.icon, and the option is made with its answer.
        assertEquals("$label: the icon is swapped once", 1, made.count { it.referenceText() == HIDE_ICON })
        val swap = made.indexOfFirst { it.referenceText() == HIDE_ICON }
        val downloadIcon = made[swap - 1] as OneRegisterInstruction
        assertTrue("$label: Download's icon is read first",
            made[swap - 2].opcode == Opcode.INVOKE_VIRTUAL && made[swap - 2].referenceText()!!.startsWith("$OPTION->"))
        assertEquals("$label: the swap is handed Download's icon", downloadIcon.registerA, (made[swap] as Instruction35c).registerC)
        val swapped = made[swap + 1] as OneRegisterInstruction
        assertEquals(Opcode.MOVE_RESULT, made[swap + 1].opcode)
        assertTrue("$label: before the option is made", made.indexOf(init) > swap + 1)
        assertEquals("$label: the option draws the swapped icon", swapped.registerA, (init as Instruction35c).registerF)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

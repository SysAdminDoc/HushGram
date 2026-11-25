/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.follow

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Follow without the review sheet" (#867): the "Review this account before following" sheet's
 * builder asks FollowReview.skip first, with its Follow listener, and returns before it shows the
 * sheet on a yes, on every build of the declared version. The builder is found by its shape alone,
 * so the whole app is searched to show the shape picks one method.
 */
class FollowReviewHookTest {
    @Test
    fun eachDeclaredBuildSkipsTheReviewSheet() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildSkipsTheReviewSheetToo() {
        val others = Fixtures.otherBuilds()
        assertEquals(Fixtures.OTHER_BUILDS, others.size)
        for (base in others) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val sheets = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == ON_CLICK } }) { it.isSheetShape() }
        assertEquals("$label: one static (Context, listener, listener, session, object, object) void in the app", 1, sheets.size)
        val stock = sheets.single()
        val stockCode = stock.implementation!!.instructions.toList()
        // scripts/injected-mutation-contracts.txt picks the builder by its shape, this literal and this call.
        assertTrue("$label: the builder reads the account's field", stockCode.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == ACCOUNT_FIELD })
        assertTrue("$label: and its button text", stockCode.any { it.referencedOrNull() == GET_STRING })
        assertTrue("$label: the sheet shows over an activity", stockCode.any {
            it.opcode == Opcode.INSTANCE_OF && it.referencedOrNull() == "Landroid/app/Activity;"
        })

        val holder = FixtureDex.classes(bundle, setOf(stock.definingClass)).getValue(stock.definingClass)
        val context = PatchContexts.of(listOf(holder, ExtensionDex.classDef(FOLLOW_REVIEW)))
        val found = context.findReviewSheet()
        assertEquals(label, "${stock.definingClass}->${stock.name}", "${found.definingClass}->${found.name}")
        skipFollowReview(found)

        val code = found.implementation!!.instructions.toList()
        assertEquals("$label: skip first", SKIP_REVIEW, code[0].referencedOrNull())
        val skip = code[0] as RegisterRangeInstruction
        assertEquals("$label: skip takes one value", 1, skip.registerCount)
        assertEquals("$label: the Follow listener, the second parameter", stock.implementation!!.registerCount - 6 + 1, skip.startRegister)
        assertEquals(label, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
        assertEquals(label, 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(label, 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$label: a no goes on to Instagram's own code", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$label: Instagram's own code follows", stockCode.first().opcode, code[4].opcode)
        assertEquals("$label: once", 1, code.count { it.referencedOrNull() == SKIP_REVIEW })
        assertEquals("$label: nothing else moved", stockCode.size + 4, code.size)

        println("$label: ${stock.definingClass}->${stock.name} asks FollowReview.skip with v${skip.startRegister}")
    }

    private fun Method.isSheetShape(): Boolean {
        val types = parameterTypes.map(Any::toString)
        return AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && types.size == 6 &&
            types.subList(0, 4) == listOf("Landroid/content/Context;", ON_CLICK, ON_CLICK, USER_SESSION) &&
            types.drop(4).all { it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }
    }

    private fun Instruction.referencedOrNull(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val ON_CLICK = "Landroid/content/DialogInterface\$OnClickListener;"
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
        const val GET_STRING = "Landroid/content/Context;->getString(I)Ljava/lang/String;"
        const val ACCOUNT_FIELD = 0x46ae0f6e
    }
}

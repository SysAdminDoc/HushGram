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
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Follow button's tap action, which a profile's Follow button and each Followers or Following
 * row's Follow or Follow back button run through, asks FollowConfirm.hold first and returns while it
 * holds the tap, on every build of the declared version. The extension's stubs reach the button's
 * view, the follow status, the username and the action itself.
 */
class AskBeforeFollowHookTest {
    @Test
    fun eachDeclaredBuildAsksBeforeAFollow() {
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
    fun eachOtherBuildAsksBeforeAFollowToo() {
        val others = Fixtures.otherBuilds()
        assertEquals(Fixtures.OTHER_BUILDS, others.size)
        for (base in others) builds(base, base.parentFile.name)
    }

    /**
     * Slices the build to what the patch reads: FollowButtonBase, its helper, the classes the tap
     * action takes, the helper's field types, the status enum and its string pools, and the user
     * model with the classes it extends. The patch then finds and changes the action on its own.
     */
    private fun builds(bundle: File, label: String) {
        val have = LinkedHashMap<String, ClassDef>()
        fun load(types: Set<String>) {
            val wanted = types.filter { it !in have && it.startsWith("L") && !it.startsWith("Ljava/") }.toSet()
            if (wanted.isNotEmpty()) have.putAll(FixtureDex.classes(bundle, wanted))
        }
        load(setOf(FOLLOW_BUTTON_BASE))
        val helperType = have.getValue(FOLLOW_BUTTON_BASE).methods.single { it.name == "getHelper" && it.parameterTypes.isEmpty() }.returnType
        load(setOf(helperType))
        val helper = have.getValue(helperType)
        val stock = helper.methods.single { method ->
            val types = method.parameterTypes.map(Any::toString)
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && types.size == 9 &&
                types[1] == USER_SESSION && types[2] == MEDIA && types[7] == "Ljava/lang/Double;" && types[8] == "Ljava/lang/String;"
        }
        val parameters = stock.parameterTypes.map(Any::toString)
        val user = parameters[6]
        val statuses = helper.methods.filter { it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, user) }.map { it.returnType }
        load(parameters.toSet() + helper.fields.map { it.type } + statuses)
        var at: String? = user
        while (at != null && at != "Ljava/lang/Object;") {
            load(setOf(at))
            val classDef = have[at] ?: break
            load(classDef.fields.map { it.type }.toSet())
            at = classDef.superclass
        }
        val pooled = FixtureDex.withStringPools(bundle, statuses.mapNotNull { have[it] })
        val context = PatchContexts.of((have.values + pooled).distinctBy { it.type } + ExtensionDex.classDef(FOLLOW_CONFIRM))

        // scripts/injected-mutation-contracts.txt picks the action by its shape and this call.
        assertTrue("$label: the action checks the button is on screen", stock.implementation!!.instructions.any {
            it.referencedOrNull() == "Landroid/view/View;->isAttachedToWindow()Z"
        })
        val found = context.findFollowButton()
        assertEquals(label, stock.name, found.action.name)
        askBeforeFollow(found)

        val code = found.action.implementation!!.instructions.toList()
        assertTrue("$label: hold first", code[0].calls(HOLD_FOLLOW))
        val hold = code[0] as RegisterRangeInstruction
        assertEquals("$label: hold takes the helper and its nine values", 10, hold.registerCount)
        assertEquals("$label: starting at the helper itself", stock.implementation!!.registerCount - 10, hold.startRegister)
        assertEquals(label, listOf(Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID), code.subList(1, 4).map { it.opcode })
        assertEquals(label, 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals(label, 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$label: Instagram's own code follows", stock.implementation!!.instructions.first().opcode, code[4].opcode)
        assertEquals("$label: once", 1, code.count { it.calls(HOLD_FOLLOW) })

        val view = context.stub(VIEW_OF_STUB)
        assertEquals("$label: the stub reads the button", "$helperType->${found.button.name}:${found.button.type}", view[1].referenced())
        assertTrue("$label: and asks it for its view", view.any { it.referencedOrNull() == "${found.button.type}->getView()Landroid/view/View;" })
        val status = context.stub(STATUS_OF_STUB)
        assertEquals("$label: the stub reads the status", "$helperType->${found.status.name}($USER_SESSION$user)${found.status.returnType}",
            status[3].referenced())
        val username = context.stub(USERNAME_OF_STUB)
        assertEquals(label, "${found.tree.definingClass}->${found.tree.name}:${found.tree.type}", username[1].referenced())
        assertEquals(label, "${found.stringGetter.definingClass}->${found.stringGetter.name}(I)Ljava/lang/String;", username[3].referenced())
        val again = context.stub(FOLLOW_AGAIN_STUB)
        assertEquals(label, List(9) { Opcode.CHECK_CAST } + listOf(Opcode.INVOKE_VIRTUAL_RANGE, Opcode.RETURN_VOID), again.take(11).map { it.opcode })
        assertEquals("$label: the stub calls the action again", "$helperType->${stock.name}(${parameters.joinToString("")})V", again[9].referenced())
        assertEquals(label, 10, (again[9] as RegisterRangeInstruction).registerCount)

        println("$label: $helperType->${stock.name} holds; status ${found.status.name}, button ${found.button.name}:${found.button.type}, " +
            "username ${found.tree.definingClass}->${found.tree.name} ${found.stringGetter.definingClass}->${found.stringGetter.name}")
    }

    private fun Instruction.calls(reference: String) = referencedOrNull() == reference

    private fun Instruction.referencedOrNull(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.referenced(): String = referencedOrNull()!!

    private fun app.morphe.patcher.patch.BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(FOLLOW_CONFIRM).methods.single { it.name == name }.implementation!!.instructions.toList()

    private companion object {
        const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
        const val MEDIA = "Lcom/instagram/feed/media/Media;"
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Long press a profile picture to view it (#737): each call giving the profile header's picture, or
 * the frame around it, its long press listener goes to ProfilePictureHold.hold on the same registers,
 * in the header's bind and in the Compose avatar binder it calls, and nothing else in either moves.
 */
class ProfilePictureHoldHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PICTURE_HOLD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$PICTURE_HOLD->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOLD_HOOK is not in the extension: $declared", HOLD_HOOK in declared)
    }

    @Test
    fun aBuildWithoutTheHeaderBindLeavesTheRestOfThePatchIn() {
        assertNull(PatchContexts.of(listOf(ExtensionDex.classDef(PICTURE_HOLD))).findProfilePictureHold())
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() {
        val missing = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findProfilePictureHold() }
        assertTrue(missing.message, missing.message!!.contains("the extension has no $PICTURE_HOLD"))
    }

    /** With the Compose binder missing, the legacy header keeps the hook. */
    @Test
    fun aBuildWithoutTheComposeBinderKeepsTheLegacyHeaders() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(anchorClasses(bundle) + ExtensionDex.classDef(PICTURE_HOLD))
        val found = context.findProfilePictureHold()!!
        assertEquals("only the header bind", 1, found.size)
        assertEquals("the header bind, which takes a context first", "Landroid/content/Context;", found.single().parameters.first())
    }

    @Test
    fun eachDeclaredBuildHooksBothBinders() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooks(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksBothBindersToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    private fun anchorClasses(bundle: File): List<ClassDef> =
        (FixtureDex.classesLoading(bundle, LEGACY_AVATAR) + FixtureDex.classesLoading(bundle, COMPOSE_AVATAR)).distinctBy { it.type }

    private fun hooks(bundle: File, label: String) {
        val anchors = anchorClasses(bundle)
        val byType = anchors.associateBy { it.type }
        val binds = anchors.flatMap { it.methods }.filter { method ->
            method.code().any { it.referenceText() == SET_LONG_CLICK } &&
                FixtureDex.loads(method, LEGACY_AVATAR, byType) && FixtureDex.loads(method, COMPOSE_AVATAR, byType)
        }
        val bind = binds.singleOrNull() ?: throw AssertionError("$label: expected one header bind, found ${binds.map { it.name }}")
        // Every class the bind calls with a Fragment first, which the Compose binder is one of.
        val called = bind.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.parameterTypes.firstOrNull()?.toString() == FRAGMENT_TYPE }.map { it.definingClass }.toSet()
        val context = PatchContexts.of((anchors + FixtureDex.classes(bundle, called).values).distinctBy { it.type } + ExtensionDex.classDef(PICTURE_HOLD))

        val found = context.findProfilePictureHold() ?: throw AssertionError("$label: the patch found no picture listener")
        assertEquals("$label: the header bind and the Compose binder", 2, found.size)
        val (legacy, compose) = found
        assertEquals("$label: the header bind first", "${bind.definingClass}->${bind.name}", "${legacy.owner}->${legacy.name}")
        assertTrue("$label: the Compose binder is one the bind calls", compose.owner in called)

        // The rules in scripts/injected-mutation-contracts.txt pick these two methods alone.
        assertTrue("$label: the bind is static", AccessFlags.STATIC.isSet(bind.accessFlags))
        assertTrue("$label: the bind's shape ${legacy.parameters}", shaped(LEGACY_SHAPE, legacy.parameters, legacy.returnType))
        val composeRule = FixtureDex.methodsWhere(bundle, { true }) { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.parameterTypes.size == 5 &&
                method.parameterTypes.first().toString() == FRAGMENT_TYPE &&
                shaped(COMPOSE_SHAPE, method.parameterTypes.map { it.toString() }, method.returnType) &&
                COMPOSE_NUMBERS.all { number -> method.code().any { it.wideNumber() == number } }
        }.map { "${it.definingClass}->${it.name}" }
        assertEquals("$label: the Compose binder's rule", listOf("${compose.owner}->${compose.name}"), composeRule)

        for (site in found) {
            val where = "$label: ${site.owner}->${site.name}"
            val before = context.method(site)
            val stock = before.code()
            val calls = stock.indices.filter { stock[it].referenceText() == SET_LONG_CLICK }
            assertEquals("$where: both of its long press listener calls are the picture's", calls, site.sites)
            assertEquals("$where: two sites", 2, site.sites.size)
            val registers = site.sites.map { stock[it].arguments() }
            val path = NeutralNativePath(before)

            context.applyProfilePictureHold(listOf(site))

            val method = context.method(site)
            val code = method.code()
            assertTrue("$where: no long press listener is set around the hook", code.none { it.referenceText() == SET_LONG_CLICK })
            assertEquals("$where: the hook in each one's place", site.sites, code.indices.filter { code[it].referenceText() == HOLD_HOOK })
            for ((at, was) in site.sites.zip(registers)) {
                assertTrue("$where: a static call at $at", code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE)
                assertEquals("$where: the view and the listener at $at", was, code[at].arguments())
            }
            path.assertPreserved(where, method, emptySet(), site.sites.toSet())
        }
    }

    private fun shaped(shape: String, parameters: List<String>, returnType: String): Boolean {
        val pattern = shape.split("*").joinToString("[^;]*") { Regex.escape(it) }
        return Regex(pattern).matches("(${parameters.joinToString("")})$returnType")
    }

    private fun BytecodePatchContext.method(site: PictureHoldMethod): MutableMethod =
        mutableClassDefBy(site.owner).methods.single {
            it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.wideNumber(): Long? =
        if (this is WideLiteralInstruction && opcode.setsWideRegister()) wideLiteral else null

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    private companion object {
        const val FRAGMENT_TYPE = "Landroidx/fragment/app/Fragment;"
        // As the contract rules write them.
        const val LEGACY_SHAPE = "(Landroid/content/Context;Landroid/view/View;LX/*;Lcom/instagram/common/session/UserSession;" +
            "LX/*;LX/*;LX/*;LX/*;LX/*;LX/*;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;IZ)V"
        const val COMPOSE_SHAPE = "(Landroidx/fragment/app/Fragment;LX/*;LX/*;LX/*;LX/*;)V"
        val COMPOSE_NUMBERS = listOf(0x8109600006361aL, 0x810f47000a5516L)
    }
}

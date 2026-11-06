/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.gridjump

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.download.profile.profileBarFills
import app.morphe.patches.instagram.profile.postslist.FRAGMENT
import app.morphe.patches.instagram.profile.postslist.GRID_CELL
import app.morphe.patches.instagram.profile.postslist.POSTS_LIST
import app.morphe.patches.instagram.profile.postslist.PROFILE_MEDIA_TAB
import app.morphe.patches.instagram.profile.postslist.PROFILE_TAB_CONTROLLER
import app.morphe.patches.instagram.profile.threads.PROFILE_ACTION_BAR
import app.morphe.patches.instagram.profile.threads.USER_DETAIL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jump a profile's posts to a date (#498): the grid's request has its max_id passed through the
 * extension first thing, and the stub reaches the same reload Instagram's tab switch makes.
 */
class ProfileGridJumpHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(GRID_JUMP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$GRID_JUMP->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(JUMP_RESUMED, JUMP_BOUND, JUMP_MAX_ID)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(ExtensionDex.classDef(GRID_JUMP))).findGridJump() }
        assertTrue(refused.message, refused.message!!.contains(TIMELINE_QUERY))
    }

    @Test
    fun eachDeclaredBuildHooksTheRequestTheTabAndTheBar() {
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
    fun eachOtherBuildHooksThemToo() {
        for (base in Fixtures.otherBuilds()) hooks(base, base.parentFile.name)
    }

    private fun hooks(bundle: File, label: String) {
        val context = PatchContexts.of(load(bundle) + ExtensionDex.classDef(GRID_JUMP) + ExtensionDex.classDef(POSTS_LIST))
        val site = context.findGridJump()

        // What the two values of the request are, as the method declares them.
        assertEquals("$label: the request is the grid's", "Lcom/instagram/common/session/UserSession;", site.request.parameterTypes.first().toString())
        assertEquals("$label: the user id", 4, site.user)
        assertEquals("$label: the max_id", 5, site.maxId)

        val request = { context.mutableClassDefBy(site.request.definingClass).methods.single { it.name == site.request.name && it.parameters.size == site.request.parameters.size } }
        val resume = { context.mutableClassDefBy(PROFILE_MEDIA_TAB).methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() } }
        val binder = { context.mutableClassDefBy(PROFILE_ACTION_BAR).methods.single { it.name == site.bar.name && it.parameterTypes == site.bar.parameterTypes } }
        val requestBefore = NeutralNativePath(request())
        val resumeBefore = NeutralNativePath(resume())
        val binderBefore = NeutralNativePath(binder())
        val superCall = resume().code().indexOfFirst { it.opcode == Opcode.INVOKE_SUPER && it.referenceText()?.endsWith(";->onResume()V") == true }
        val fill = binder().profileBarFills().single()

        context.applyGridJump(site)

        val steps = request().code()
        assertEquals(
            "$label: the request's max_id goes through the extension first",
            listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_OBJECT_FROM16),
            steps.take(5).map { it.opcode },
        )
        assertEquals("$label: maxId", JUMP_MAX_ID, steps[2].referenceText())
        assertEquals("$label: maxId once", 1, steps.count { it.referenceText() == JUMP_MAX_ID })
        requestBefore.assertPreserved(label, request(), setOf(0, 1, 2, 3, 4))

        val tab = resume().code()
        val tabHooks = tab.indices.filter { tab[it].referenceText() == JUMP_RESUMED }
        assertEquals("$label: the tab goes to the extension right after its super call", listOf(superCall + 1), tabHooks)
        assertEquals("$label: not first, where Profile posts as a list goes", true, tabHooks.single() > 0)
        resumeBefore.assertPreserved(label, resume(), setOf(superCall + 1))

        val bar = binder().code()
        val barHooks = bar.indices.filter { bar[it].referenceText() == JUMP_BOUND }
        assertEquals("$label: the row goes to the extension right after the fill", listOf(fill.at + 1), barHooks)
        assertEquals("$label: the right row", listOf(fill.right), bar[fill.at + 1].arguments())
        binderBefore.assertPreserved(label, binder(), setOf(fill.at + 1))

        // The stub makes the call Instagram's own tab switch makes: the tab's feed, pull and first page both on.
        val stub = context.mutableClassDefBy(GRID_JUMP).methods.single { it.name == JUMP_RELOAD }
        val calls = stub.code().mapNotNull { it.referenceText() }
        assertEquals(
            "$label: the stub's steps",
            listOf(
                PROFILE_MEDIA_TAB, site.holder.toString(), site.screen.toString(), site.feed.toString(),
                "${site.load.definingClass}->${site.load.name}(${site.load.parameterTypes.joinToString("")})V",
            ),
            calls,
        )
        assertEquals("$label: the reload's on the profile screen", USER_DETAIL, site.load.definingClass)
        assertEquals("$label: the stub still ends in its own return", Opcode.RETURN_VOID, stub.code().last().opcode)
        assertTrue("$label: the stub's registers fit four bits", (stub.implementation?.registerCount ?: 99) <= 16)
    }

    /** The tab with its superclasses up to Fragment and what it holds, the screen, the bar, and the request with its string pools. */
    private fun load(bundle: File): List<ClassDef> {
        val supers = HashMap<String, String?>()
        FixtureDex.forEach(bundle) { dex -> for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass) }
        val chain = mutableSetOf<String>()
        var next = supers[PROFILE_MEDIA_TAB]
        while (next != null && next in supers && chain.add(next)) next = supers[next]
        assertTrue("${bundle.name}: the posts tab is a Fragment", FRAGMENT in chain)
        val named = FixtureDex.classes(bundle, setOf(PROFILE_MEDIA_TAB, PROFILE_TAB_CONTROLLER, GRID_CELL, PROFILE_ACTION_BAR, USER_DETAIL) + chain)
        val held = FixtureDex.classes(bundle, named.getValue(PROFILE_MEDIA_TAB).fields.map { it.type }.filter { it.startsWith("L") }.toSet())
        val request = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, TIMELINE_QUERY))
        return (named.values + held.values + request).distinctBy { it.type }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}

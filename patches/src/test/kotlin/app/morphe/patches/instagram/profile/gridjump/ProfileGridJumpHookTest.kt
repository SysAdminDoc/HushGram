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
import app.morphe.patches.instagram.misc.extension.jumpTargets
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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jump a profile's posts to a date (#498): the profile's load asks the extension first thing whether a
 * page takes the jump's route, that route's request can have its path swapped, and the stub reaches
 * the same reload Instagram's tab switch makes.
 */
class ProfileGridJumpHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(GRID_JUMP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$GRID_JUMP->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(JUMP_RESUMED, JUMP_BOUND, JUMP_REST_MAX_ID, JUMP_REST_PATH)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val refused = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(ExtensionDex.classDef(GRID_JUMP))).findGridJump() }
        assertTrue(refused.message, refused.message!!.contains(REST_SOURCE))
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
        val rest = site.rest

        // What the request takes and what the load is, as the methods declare them.
        assertEquals(
            "$label: the request takes the session, the user's id and the cursor",
            listOf("Lcom/instagram/common/session/UserSession;", "Ljava/lang/String;", "Ljava/lang/String;"),
            rest.request.parameterTypes.map { it.toString() },
        )
        assertEquals("$label: the load takes a count and two flags", listOf("I", "Z", "Z"), rest.entry.parameterTypes.drop(2).map { it.toString() })
        assertEquals("$label: the fetch is the load's own", rest.entry.definingClass, rest.fetch.definingClass)

        val entry = { context.mutableClassDefBy(rest.entry.definingClass).methods.single { it.name == rest.entry.name && it.parameterTypes == rest.entry.parameterTypes } }
        val request = { context.mutableClassDefBy(rest.request.definingClass).methods.single { it.name == rest.request.name && it.parameterTypes == rest.request.parameterTypes } }
        val resume = { context.mutableClassDefBy(PROFILE_MEDIA_TAB).methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() } }
        val binder = { context.mutableClassDefBy(PROFILE_ACTION_BAR).methods.single { it.name == site.bar.name && it.parameterTypes == site.bar.parameterTypes } }
        val entryBefore = NeutralNativePath(entry())
        val requestBefore = NeutralNativePath(request())
        val resumeBefore = NeutralNativePath(resume())
        val binderBefore = NeutralNativePath(binder())
        val superCall = resume().code().indexOfFirst { it.opcode == Opcode.INVOKE_SUPER && it.referenceText()?.endsWith(";->onResume()V") == true }
        val fill = binder().profileBarFills().single()

        context.applyGridJump(site)

        val steps = entry().code()
        assertEquals(
            "$label: the load asks the extension first, and takes the route only on its answer",
            listOf(
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_FROM16,
                Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET_OBJECT,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IGET_OBJECT, Opcode.IGET_OBJECT,
                Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.MOVE_FROM16,
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ,
                Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_FROM16,
                Opcode.INVOKE_DIRECT, Opcode.RETURN_VOID, Opcode.NOP,
            ),
            steps.take(ENTRY_HOOK_SIZE).map { it.opcode },
        )
        assertEquals("$label: the user's id", rest.userId.toString(), steps[6].referenceText())
        assertEquals("$label: the tab's paging state", rest.state.toString(), steps[7].referenceText())
        assertEquals("$label: its cursor", rest.cursor.toString(), steps[10].referenceText())
        assertEquals("$label: whether it's free", rest.free.toString(), steps[12].referenceText())
        assertEquals("$label: the hook", JUMP_REST_MAX_ID, steps[15].referenceText())
        assertEquals("$label: the hook once", 1, steps.count { it.referenceText() == JUMP_REST_MAX_ID })
        assertEquals("$label: the request", rest.request.toString(), steps[19].referenceText())
        assertEquals("$label: the fetch", rest.fetch.toString(), steps[22].referenceText())
        assertTrue("$label: both turn-downs land on Instagram's route", ENTRY_HOOK_SIZE - 1 in entry().jumpTargets())
        entryBefore.assertPreserved(label, entry(), (0 until ENTRY_HOOK_SIZE).toSet())

        val path = request().code()
        val pathHooks = path.indices.filter { path[it].referenceText() == JUMP_REST_PATH }
        assertEquals("$label: the path goes to the extension right where it's loaded", listOf(rest.pathAt), pathHooks)
        assertEquals("$label: the path's register", listOf(rest.pathRegister), path[rest.pathAt].arguments())
        assertEquals("$label: and comes back", Opcode.MOVE_RESULT_OBJECT, path[rest.pathAt + 1].opcode)
        requestBefore.assertPreserved(label, request(), setOf(rest.pathAt, rest.pathAt + 1))

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

    /** The tab with its superclasses up to Fragment and what it holds, the screen, the bar, and the route with what it reads and its string pools. */
    private fun load(bundle: File): List<ClassDef> {
        val supers = HashMap<String, String?>()
        FixtureDex.forEach(bundle) { dex -> for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass) }
        val chain = mutableSetOf<String>()
        var next = supers[PROFILE_MEDIA_TAB]
        while (next != null && next in supers && chain.add(next)) next = supers[next]
        assertTrue("${bundle.name}: the posts tab is a Fragment", FRAGMENT in chain)
        val named = FixtureDex.classes(bundle, setOf(PROFILE_MEDIA_TAB, PROFILE_TAB_CONTROLLER, GRID_CELL, PROFILE_ACTION_BAR, USER_DETAIL) + chain)
        val held = FixtureDex.classes(bundle, named.getValue(PROFILE_MEDIA_TAB).fields.map { it.type }.filter { it.startsWith("L") }.toSet())
        val hosts = FixtureDex.classesLoading(bundle, REST_SOURCE)
        val read = hosts.flatMap { host -> host.methods.flatMap { it.code() } }
            .mapNotNull { (it.referenceOf() as? FieldReference)?.definingClass }.toSet()
        val route = hosts + FixtureDex.classesLoading(bundle, TAGGED_POSTS_PATH) + FixtureDex.classes(bundle, read).values
        return (named.values + held.values + FixtureDex.withStringPools(bundle, route)).distinctBy { it.type }
    }

    private fun Instruction.referenceOf() = (this as? ReferenceInstruction)?.reference

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}

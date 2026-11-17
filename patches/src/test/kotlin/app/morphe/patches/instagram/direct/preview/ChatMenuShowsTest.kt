/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.preview

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureCache
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a chat's long press menu opens (#728), on each build's own dex: every show of Instagram's
 * long press menu below what was pressed goes to the extension with the same menu, anchor and
 * offsets, and nothing else in those methods changes.
 */
class ChatMenuShowsTest {
    @Test fun declaredBuildsHandEachMenuShowToTheExtension() = fixtures { check(it) }

    @Test fun otherBuildsHandEachMenuShowToTheExtensionToo() = otherBuilds { check(it) }

    private fun check(bundle: File) {
        val name = bundle.parentFile.name
        val classes = menuClasses(bundle)
        val context = PatchContexts.of(classes.values)
        val found = try {
            context.findMenuShows()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        val menus = classes.values.filter { popup ->
            popup.superclass == MENU_POPUP_BASE &&
                popup.methods.any { it.name == "<init>" && it.parameterTypes.map(Any::toString) == MENU_POPUP_INIT }
        }.map { it.type }.toSet()
        assertEquals("$name: one class is Instagram's long press menu", 1, menus.size)
        for (each in found) {
            assertTrue("$name: ${each.method.name} shows the menu", menuShowsIn(each.method.visualCode()) { it in menus }.isNotEmpty())
        }
        val before = found.associate { key(it.owner.type, it.method.name, it.method.parameterTypes) to it.method.visualCode().map(::text) }
        val calls = found.associate { key(it.owner.type, it.method.name, it.method.parameterTypes) to it.method.visualCode() }

        context.placeMenus(found)

        var hooked = 0
        for (each in found) {
            val id = key(each.owner.type, each.method.name, each.method.parameterTypes)
            val method = context.mutableClassDefBy(each.owner.type).methods.single {
                key(it.definingClass, it.name, it.parameterTypes) == id
            }
            val after = method.visualCode()
            val stock = calls.getValue(id)
            assertEquals("$name: ${method.name} keeps its length", stock.size, after.size)
            for (at in after.indices) {
                if (after[at].visualReference()?.toString() != MENU_SHOW) {
                    assertEquals("$name: ${method.name}[$at] is Instagram's own", before.getValue(id)[at], text(after[at]))
                    continue
                }
                hooked++
                assertEquals(Opcode.INVOKE_STATIC, after[at].opcode)
                val show = stock[at].visualReference() as MethodReference
                assertEquals("$name: the hook stands where Instagram showed the menu", "showAsDropDown", show.name)
                assertEquals("$name: the hook gets the menu, the anchor and both offsets", registers(stock[at]), registers(after[at]))
            }
            assertTrue("$name: ${method.name} shows no menu below the pressed row on its own any more",
                menuShowsIn(after) { it in menus }.isEmpty())
        }
        assertTrue("$name: some menu show is hooked", hooked > 0)
    }

    /** Without the extension's hook the patch refuses before any code changes. */
    @Test fun aMissingHookFailsBeforeAnythingChanges() = fixtures { bundle ->
        val classes = menuClasses(bundle).filterKeys { it != CHAT_MENU_PLACEMENT }
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes.values).findMenuShows() }
        assertTrue(failure.message, failure.message!!.startsWith("$PREVIEW_CHATS_PATCH: the extension has no"))
    }

    /** A build with no menu shown below what was pressed fails naming the patch. */
    @Test fun aBuildWithoutTheMenuShowFails() {
        val classes = listOf(ImmutableClassDef.of(ExtensionDex.classDef(CHAT_MENU_PLACEMENT)))
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findMenuShows() }
        assertTrue(failure.message, failure.message!!.startsWith("$PREVIEW_CHATS_PATCH: no long press menu"))
    }

    private fun key(type: String, name: String, parameters: List<CharSequence>) = "$type->$name${parameters.map(Any::toString)}"

    private fun registers(instruction: Instruction): List<Int> {
        val call = instruction as FiveRegisterInstruction
        return listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    }

    /** What an instruction does and on what. */
    private fun text(instruction: Instruction) = "${instruction.opcode} ${instruction.visualReference() ?: ""}"

    companion object {
        private val cached = FixtureCache<Map<String, ClassDef>>()

        /** Every class calling showAsDropDown(View, int, int), Instagram's PopupWindows and the extension's hook. */
        internal fun menuClasses(bundle: File): Map<String, ClassDef> = cached.get(bundle) {
            val callers = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.name == "showAsDropDown" } }) { method ->
                method.visualCode().any { (it.visualReference() as? MethodReference)?.name == "showAsDropDown" }
            }
            val classes = FixtureDex.classes(bundle, callers.map { it.definingClass }.toSet()).toMutableMap()
            for (popup in FixtureDex.subclasses(bundle, setOf(MENU_POPUP_BASE))) classes.putIfAbsent(popup.type, popup)
            classes[CHAT_MENU_PLACEMENT] = ImmutableClassDef.of(ExtensionDex.classDef(CHAT_MENU_PLACEMENT))
            classes
        }
    }
}

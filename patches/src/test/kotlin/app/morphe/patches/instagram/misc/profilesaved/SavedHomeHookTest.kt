/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.profilesaved

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.instagram.misc.extension.SETTINGS_STATUS
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Saved on All and the + (#762): the Saved link's screen, which the bookmark starts, makes the menu's
 * tabbed Saved when the bookmark started it, that screen asks the extension for its back arrow when
 * nothing sits under it, and both Saved screens ask the extension before they make and add the + for
 * a new collection. On every declared build and the other builds of that version, and nothing else
 * changes.
 */
class SavedHomeHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(FROM_BOOKMARK, PICK_SCREEN, HIDE_NEW_COLLECTION, SHOW_BACK)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachBuildOfADeclaredVersionOpensSavedOnAllAndCanHideThePlus() {
        val bundles = declaredBundles()
        val others = Fixtures.otherBuilds()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
        val checked = (bundles + others).map { checkBuild(it) }
        assertEquals("every build was checked", bundles.size + others.size, checked.size)
    }

    /** Both hooks found, the patch switches all three statuses on. */
    @Test
    fun thePatchSwitchesOnWhatItHooked() {
        val bundle = declaredBundles().first()
        val context = PatchContexts.of(slice(bundle) + ExtensionDex.classDef(SETTINGS_STATUS))
        savedOnProfilePatch.execute(context)
        assertEquals(1, context.status("savedOnProfile"))
        assertEquals(1, context.status("savedOnAll"))
        assertEquals(1, context.status("savedNewCollection"))
    }

    /** A build with neither Saved screen keeps the bookmark and changes nothing, and the two new rows stay out. */
    @Test
    fun aBuildWithoutTheSavedScreensKeepsTheBookmarkOnly() {
        val context = PatchContexts.of(listOf(ExtensionDex.classDef(SETTINGS_STATUS)))
        val hooked = context.hookSavedHome()
        assertFalse(hooked.opensOnAll)
        assertEquals(0, hooked.newCollectionScreens)
        assertFalse(hooked.backArrow)
        savedOnProfilePatch.execute(context)
        assertEquals(1, context.status("savedOnProfile"))
        assertEquals(0, context.status("savedOnAll"))
        assertEquals(0, context.status("savedNewCollection"))
    }

    /** Without the link's screen the + still goes on both Saved screens, and the link's screen is all Saved on All needs. */
    @Test
    fun eachPartGoesInWithoutTheOther() {
        val bundle = declaredBundles().first()
        val all = slice(bundle)
        val withoutLink = PatchContexts.of(all.filter { it.type != SAVED_LINK_SCREEN })
        val buttonsOnly = withoutLink.hookSavedHome()
        assertFalse(buttonsOnly.opensOnAll)
        assertEquals(2, buttonsOnly.newCollectionScreens)
        // The arrow only matters on the screen the link's hook hands the bookmark.
        assertFalse(buttonsOnly.backArrow)

        val screens = PatchContexts.of(all).findSavedScreens()
        // The maker names both screens in its own code, so it's still found without their classes.
        val withoutBars = PatchContexts.of(all.filter { it.type != screens.tabbed && it.type != screens.grid })
        val linkOnly = withoutBars.hookSavedHome()
        assertTrue(linkOnly.opensOnAll)
        assertEquals(0, linkOnly.newCollectionScreens)
        assertFalse(linkOnly.backArrow)
    }

    /** Returns the build's name, so the caller can count the builds it read. */
    private fun checkBuild(bundle: File): String {
        val where = "${bundle.parentFile.name}/${bundle.name}"
        val classes = slice(bundle)
        val context = PatchContexts.of(classes)
        val screens = context.findSavedScreens()
        assertNotEquals("$where: two Saved screens", screens.tabbed, screens.grid)
        val link = context.findLinkScreen(screens)
        val buttons = context.findNewCollectionButtons(screens)
        assertEquals("$where: both Saved screens' +", setOf(screens.tabbed, screens.grid), buttons.map { it.screen }.toSet())
        assertTrue("$where: the + loads its label", buttons.all { it.label != null })
        val back = context.findBackArrow(screens.tabbed)
        val before = classes.associate { it.type to context.mutableClassDefBy(it.type).methods.associate { method -> signature(it.type, method) to method.visualCode() } }
        val linkSignature = signature(SAVED_LINK_SCREEN, link.method)
        val buttonSignatures = buttons.associateBy { signature(it.screen, it.method) }
        val backSignature = signature(screens.tabbed, back.method)
        val linkAt = link.insertAt
        // The tabbed bar takes both the + and the arrow, so whichever sits later moves by the other's three.
        fun buttonAt(button: NewCollectionButton) =
            button.from + if (signature(button.screen, button.method) == backSignature && back.at < button.from) 3 else 0
        val tabbedButton = buttons.single { it.screen == screens.tabbed }
        assertEquals("$where: the arrow and the + share the tabbed bar", backSignature, signature(tabbedButton.screen, tabbedButton.method))
        val backAt = back.at + if (tabbedButton.from < back.at) 3 else 0

        val hooked = context.hookSavedHome()
        assertTrue("$where: opens on All", hooked.opensOnAll)
        assertEquals("$where: both +", 2, hooked.newCollectionScreens)
        assertTrue("$where: the back arrow", hooked.backArrow)

        // The link's screen: right after it has its grid, it asks whether the bookmark started it.
        val linkCode = method(context, SAVED_LINK_SCREEN, link.method).visualCode()
        val made = linkCode[linkAt - 2].visualReference() as MethodReference
        assertEquals("$where: the grid's maker", screens.gridMaker, descriptor(made))
        assertEquals("$where: the grid", Opcode.MOVE_RESULT_OBJECT, linkCode[linkAt - 1].opcode)
        assertEquals("$where: the grid's register", listOf(link.result), linkCode[linkAt - 1].namedRegisters())
        assertEquals("$where: asks first", FROM_BOOKMARK, linkCode[linkAt].visualReference().toString())
        assertEquals("$where: about itself", listOf(link.self), linkCode[linkAt].namedRegisters())
        assertEquals("$where: the answer", Opcode.MOVE_RESULT, linkCode[linkAt + 1].opcode)
        assertEquals("$where: no means the grid", Opcode.IF_EQZ, linkCode[linkAt + 2].opcode)
        assertEquals("$where: skips to Instagram's next instruction", linkAt + 9, target(linkCode, linkAt + 2))
        assertEquals("$where: no tab given", Opcode.CONST_4, linkCode[linkAt + 3].opcode)
        assertEquals("$where: the menu's maker", screens.menuMaker, linkCode[linkAt + 4].visualReference().toString())
        assertEquals("$where: for the screen's own session", listOf(link.session, link.scratch), linkCode[linkAt + 4].namedRegisters())
        assertEquals("$where: picks", PICK_SCREEN, linkCode[linkAt + 6].visualReference().toString())
        assertEquals("$where: between the menu's and the grid", listOf(link.scratch, link.result), linkCode[linkAt + 6].namedRegisters())
        assertEquals("$where: the pick replaces the grid", listOf(link.result), linkCode[linkAt + 7].namedRegisters())
        assertEquals("$where: as a Fragment", "Landroidx/fragment/app/Fragment;", (linkCode[linkAt + 8].visualReference() as TypeReference).type)
        assertTrue("$where: registers in reach", (linkCode.subList(linkAt, linkAt + 9).flatMap { it.namedRegisters() }).all { it <= 15 })

        // Both top bars: the + is asked about first, and skipped whole when it's left off.
        for (button in buttons) {
            val code = method(context, button.screen, button.method).visualCode()
            val at = buttonAt(button)
            val shift = at - button.from
            assertEquals("$where: ${button.screen} asks first", HIDE_NEW_COLLECTION, code[at].visualReference().toString())
            assertEquals("$where: the answer", listOf(button.scratch), code[at + 1].namedRegisters())
            assertEquals("$where: yes skips the +", Opcode.IF_NEZ, code[at + 2].opcode)
            assertEquals("$where: to right after the + is added", button.to + 4 + shift, target(code, at + 2))
            val add = code[button.to + 3 + shift].visualReference() as MethodReference
            assertEquals("$where: the add", "Landroid/view/View\$OnClickListener;", add.parameterTypes[0].toString())
            assertEquals("$where: one ask", 1, code.count { it.visualReference().toString() == HIDE_NEW_COLLECTION })
        }

        // The tabbed bar: when nothing sits under it, it asks before it hands the bar the arrow's answer.
        val backCode = method(context, screens.tabbed, back.method).visualCode()
        assertEquals("$where: Instagram's answer first", Opcode.IF_NEZ, backCode[backAt].opcode)
        assertEquals("$where: Instagram's answer", listOf(back.flag), backCode[backAt].namedRegisters())
        assertEquals("$where: a yes goes straight to the arrow", backAt + 3, target(backCode, backAt))
        assertEquals("$where: asks", SHOW_BACK, backCode[backAt + 1].visualReference().toString())
        assertEquals("$where: about the screen", listOf(back.self), backCode[backAt + 1].namedRegisters())
        assertEquals("$where: the answer", Opcode.MOVE_RESULT, backCode[backAt + 2].opcode)
        assertEquals("$where: replaces Instagram's", listOf(back.flag), backCode[backAt + 2].namedRegisters())
        val arrow = backCode[backAt + 3].visualReference() as MethodReference
        assertEquals("$where: the bar's arrow", listOf("Z"), arrow.parameterTypes.map(CharSequence::toString))
        assertEquals("$where: gets the answer", back.flag, backCode[backAt + 3].namedRegisters().last())
        assertTrue("$where: registers in reach", backCode.subList(backAt, backAt + 3).flatMap { it.namedRegisters() }.all { it <= 15 })
        assertEquals("$where: one ask", 1, backCode.count { it.visualReference().toString() == SHOW_BACK })

        for ((type, original) in before) {
            val now = context.mutableClassDefBy(type).methods.associate { signature(type, it) to it.visualCode() }
            for ((method, instructions) in original) {
                val after = now.getValue(method).toMutableList()
                val blocks = buildList {
                    if (method == linkSignature) add(linkAt to 9)
                    buttonSignatures[method]?.let { add(buttonAt(it) to 3) }
                    if (method == backSignature) add(backAt to 3)
                }
                for ((start, size) in blocks.sortedByDescending { it.first }) repeat(size) { after.removeAt(start) }
                assertEquals("$where: $method changed", shape(instructions), shape(after))
            }
        }
        return where
    }

    private companion object {
        private val slices = HashMap<File, List<ClassDef>>()

        fun declaredBundles(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        fun method(context: BytecodePatchContext, type: String, found: Method): Method =
            context.mutableClassDefBy(type).methods.single { it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameterTypes.map(CharSequence::toString) }

        fun signature(type: String, method: Method) = "$type->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

        fun descriptor(call: MethodReference) = "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}"

        /** The index [branch] jumps to. */
        fun target(code: List<Instruction>, branch: Int): Int {
            val address = IntArray(code.size + 1)
            code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
            val to = address[branch] + (code[branch] as OffsetInstruction).codeOffset
            return address.indexOf(to)
        }

        /** Code with NOPs left out and every goto read as one kind, so a widened branch or moved payload doesn't count as a change. */
        fun shape(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map {
            val kind = when (it.opcode) {
                Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
                else -> it.opcode
            }
            Triple(kind, it.visualReference()?.toString(), if (kind == Opcode.GOTO) emptyList() else it.namedRegisters())
        }

        /** The classes loading the tab key (the menu's maker and the tabbed screen, with their pools), the grid, and the Saved link's screen. */
        fun slice(bundle: File): List<ClassDef> = slices.getOrPut(bundle) {
            val kept = LinkedHashMap<String, ClassDef>()
            for (classDef in FixtureDex.classesLoading(bundle, INITIAL_TAB)) kept.putIfAbsent(classDef.type, classDef)
            val screens = PatchContexts.of(kept.values.toList()).findSavedScreens()
            for (classDef in FixtureDex.classes(bundle, setOf(screens.tabbed, screens.grid, SAVED_LINK_SCREEN)).values) kept.putIfAbsent(classDef.type, classDef)
            kept.values.toList()
        }

        fun BytecodePatchContext.status(name: String): Int =
            (classDefBy(SETTINGS_STATUS).methods.first { it.name == name }.implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral
    }
}

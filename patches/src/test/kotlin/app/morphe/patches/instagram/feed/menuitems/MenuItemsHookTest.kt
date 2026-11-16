/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.menuitems

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.reel.DOWNLOAD
import app.morphe.patches.instagram.download.reel.ELIGIBLE_MARKER
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.download.reel.ROW_MARKER
import app.morphe.patches.instagram.download.story.HELPER_NAME
import app.morphe.patches.instagram.download.video.FEED_HELPER_NAME
import app.morphe.patches.instagram.download.video.isShortMenuList
import app.morphe.patches.instagram.misc.extension.PURGE_MARKER
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Three-dot menu items (#697, #707): the hooks are in the extension, and in every build of 454 the
 * feed menu's builder hands its rows over before each return, the reel row adder asks first and
 * returns when told, and each story menu builder names its menu first and hands its labels over
 * before each return, with the icon stub calling Instagram's own icon method. A menu that moved is
 * refused by name, and with every menu gone the patch stops.
 */
class MenuItemsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(MENU_ITEMS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FEED_ROWS, REEL_ROW, STORY_MENU, STORY_LABELS)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachBuildGetsEveryMenu() {
        var checked = 0
        for (bundle in bundles()) {
            getsEveryMenu(bundle, bundle.name)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun bundles(): List<File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
    }

    /** The classes the three menus are found in, sliced from [bundle], with the extension's helper. */
    private fun classes(bundle: File): MutableList<ClassDef> {
        val classes = mutableListOf<ClassDef>(ExtensionDex.classDef(MENU_ITEMS))
        FixtureDex.forEach(bundle) { dex ->
            val marked = dex.stringSection.any { string ->
                string.startsWith("android_purge_") && PURGE_MARKER.find(string)?.groupValues?.get(1).let { it == ELIGIBLE_MARKER || it == ROW_MARKER }
            }
            val loads = dex.fieldSection.any { it.toString() == DOWNLOAD }
            val named = dex.stringSection.any { it == FEED_HELPER_NAME || it == HELPER_NAME }
            if (!marked && !loads && !named && dex.classes.none { it.type == OPTION }) return@forEach
            for (classDef in dex.classes) {
                val name = classDef.originalName()
                val wanted = classDef.type == OPTION || name == FEED_HELPER_NAME || name == HELPER_NAME || classDef.methods.any { method ->
                    method.markers().any { it == ELIGIBLE_MARKER || it == ROW_MARKER } || method.isShortMenuList() ||
                        method.code().any { it.referenceText() == DOWNLOAD }
                }
                if (wanted) classes += ImmutableClassDef.of(classDef)
            }
        }
        // The static methods R8 outlined `new ArrayList()` and getString into, in a build that has them.
        val outlined = classes.asSequence().flatMap { it.methods.asSequence() }.flatMap { it.code().asSequence() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter {
                (it.returnType == "Ljava/util/ArrayList;" && it.parameterTypes.isEmpty()) ||
                    (it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("Landroid/content/res/Resources;", "I"))
            }.map { it.definingClass }.filter { type -> classes.none { it.type == type } }.toSet()
        if (outlined.isNotEmpty()) classes += FixtureDex.classes(bundle, outlined).values
        return classes
    }

    private fun getsEveryMenu(bundle: File, label: String) {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        val parts = context.findMenuItems()
        assertEquals("$label: every menu is found", listOf("feed posts", "reels", "stories"), parts.map { it.menu })
        parts.forEach { it.write() }
        val written = classes.map { it.type }.filter { it != MENU_ITEMS }.distinct().flatMap { context.mutableClassDefBy(it).methods }

        // The feed menu's builder hands over the list it returns, right before each return.
        val feed = written.filter { method -> method.code().any { it.referenceText() == FEED_ROWS } }
        assertEquals("$label: the feed menu's builder", 1, feed.size)
        val built = feed.single().code()
        val returns = built.indices.filter { built[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: one hand-over per return", returns.size, built.count { it.referenceText() == FEED_ROWS })
        for (at in returns) {
            val call = built[at - 1]
            assertEquals("$label: the rows before the return at $at", FEED_ROWS, call.referenceText())
            assertEquals("$label: the list returned at $at", (built[at] as OneRegisterInstruction).registerA, (call as RegisterRangeInstruction).startRegister)
        }

        // The reel row adder asks with its option first and returns when told.
        val reel = written.filter { method -> method.code().any { it.referenceText() == REEL_ROW } }
        assertEquals("$label: the reel row adder", 1, reel.size)
        val adder = reel.single()
        assertTrue("$label: the adder holds the row marker", ROW_MARKER in adder.markers())
        val code = adder.code()
        val types = adder.parameterTypes.map(Any::toString)
        val first = adder.implementation!!.registerCount - 1 - types.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val option = first + 1 + types.take(types.indexOf(OPTION)).sumOf { if (it == "J" || it == "D") 2 else 1 }
        assertEquals("$label: reads the option", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("$label: the option's register", option, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$label: asks the extension", REEL_ROW, code[1].referenceText())
        assertEquals(Opcode.MOVE_RESULT, code[2].opcode)
        assertEquals(Opcode.IF_EQZ, code[3].opcode)
        assertEquals(Opcode.RETURN_VOID, code[4].opcode)

        // Each story menu builder names its menu first and hands its labels over before each return.
        val helper = classes.single { it.originalName() == HELPER_NAME }.type
        val builders = context.mutableClassDefBy(helper).methods.filter { it.returnType == "[Ljava/lang/CharSequence;" && AccessFlags.STATIC.isSet(it.accessFlags) && helper in it.parameterTypes.map(Any::toString) }
        assertTrue("$label: the story menu's builders", builders.isNotEmpty())
        for (builder in builders) {
            val body = builder.code()
            val parameters = builder.parameterTypes.map(Any::toString)
            val menu = builder.implementation!!.registerCount - parameters.sumOf { if (it == "J" || it == "D") 2 else 1 } +
                parameters.take(parameters.indexOf(helper)).sumOf { if (it == "J" || it == "D") 2 else 1 }
            assertEquals("$label: ${builder.name} names its menu first", STORY_MENU, body[0].referenceText())
            assertEquals("$label: ${builder.name}'s menu", menu, (body[0] as RegisterRangeInstruction).startRegister)
            val ends = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
            assertTrue("$label: ${builder.name} returns", ends.isNotEmpty())
            for (at in ends) {
                val labels = (body[at] as OneRegisterInstruction).registerA
                assertEquals("$label: ${builder.name} hands over before $at", STORY_LABELS, body[at - 2].referenceText())
                assertEquals(labels, (body[at - 2] as RegisterRangeInstruction).startRegister)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, body[at - 1].opcode)
                assertEquals(labels, (body[at - 1] as OneRegisterInstruction).registerA)
            }
        }

        val stubs = context.mutableClassDefBy(MENU_ITEMS).methods
        fun stub(name: String) = stubs.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }.code()
        assertTrue("$label: reportOption", stub("reportOption").any { it.referenceText() == REPORT })
        val icon = stub("storyIcon").mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.single()
        assertEquals("$label: storyIcon asks the story menu", helper, icon.definingClass)
        assertEquals("$label: for an icon", "I", icon.returnType)
    }

    private fun firstBundle(): File? = bundles().firstOrNull()

    private fun refuses(expected: String, find: () -> Unit) {
        try {
            find()
            fail("expected a refusal naming \"$expected\"")
        } catch (refusal: PatchException) {
            val message = refusal.message.orEmpty()
            assertTrue(message, message.startsWith("Three-dot menu items: "))
            assertTrue(message, message.contains(expected))
        }
    }

    /** A story menu whose icon method went, or can't be called from the extension, is refused by name. */
    @Test
    fun aStoryMenuWithoutItsIconMethodIsRefused() {
        val bundle = firstBundle() ?: return
        val classes = classes(bundle)
        val helper = classes.single { it.originalName() == HELPER_NAME }
        fun isIcon(method: Method) = method.returnType == "I" && AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.map(Any::toString) == listOf(helper.type, "Ljava/lang/CharSequence;")
        assertEquals("one icon method to take away", 1, helper.methods.count(::isIcon))
        val without = ImmutableClassDef(helper.type, helper.accessFlags, helper.superclass, helper.interfaces, null, null,
            helper.fields, helper.methods.filterNot(::isIcon))
        refuses("giving a label its icon") { PatchContexts.of(classes - helper + without).findStoryMenuItems() }

        val hidden = ImmutableClassDef(helper.type, helper.accessFlags and AccessFlags.PUBLIC.value.inv(), helper.superclass,
            helper.interfaces, null, null, helper.fields, helper.methods)
        refuses("isn't public") { PatchContexts.of(classes - helper + hidden).findStoryMenuItems() }
    }

    /** A reel row adder that no longer takes one option is refused by name. */
    @Test
    fun aReelRowAdderWithoutItsOptionIsRefused() {
        val bundle = firstBundle() ?: return
        val classes = classes(bundle)
        val owner = classes.single { classDef -> classDef.methods.any { ROW_MARKER in it.markers() } }
        val methods = owner.methods.map { method ->
            if (ROW_MARKER !in method.markers()) return@map method
            val parameters = method.parameters.map { parameter ->
                ImmutableMethodParameter(if (parameter.type == OPTION) "Ljava/lang/Object;" else parameter.type, null, null)
            }
            ImmutableMethod(method.definingClass, method.name, parameters, method.returnType, method.accessFlags, null, null, method.implementation)
        }
        val moved = ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, null, null, owner.fields, methods)
        refuses("the adder of one reel menu row") { PatchContexts.of(classes - owner + moved).findReelMenuItems() }
    }

    /** With none of the three menus in the app, the patch stops rather than going in doing nothing. */
    @Test
    fun noMenuAtAllStopsThePatch() {
        refuses("found none of the menus") { PatchContexts.of(listOf(ExtensionDex.classDef(MENU_ITEMS))).findMenuItems() }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

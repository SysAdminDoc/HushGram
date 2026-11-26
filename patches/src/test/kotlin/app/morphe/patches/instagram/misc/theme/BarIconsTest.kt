/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.profilesaved.EXPLORE_ACTION_BAR
import app.morphe.patches.instagram.misc.profilesaved.EXPLORE_SAVED_STUB
import app.morphe.patches.instagram.misc.profilesaved.findExploreSaved
import app.morphe.patches.instagram.reels.tab.TOASTING_BADGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A screen's top bar, a profile's tabs and the Search tab's Saved bookmark hand the colors their icons
 * are given to the extension, which answers the picked color with Icons in the accent color on (#187).
 */
class BarIconsTest {
    /**
     * Each call of the top bar's SRC_ATOP filter maker becomes a call of the extension's with the same
     * register, the range form kept, and each ImageView.setColorFilter(int) a call of the extension's
     * with the view and the color. Another maker, a text color and the filter laid on a view keep
     * Instagram's code. No bar, or a bar making no icon's color, is left as it is, and an extension
     * without the hooks refuses.
     */
    @Test
    fun theTopBarsIconColorsGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(actionBar(), filterMaker(), otherMaker(), extension()))
        context.tintActionBarIcons()
        val methods = context.mutableClassDefBy(ACTION_BAR).methods.associateBy { it.name }

        val setter = methods.getValue("A1P").code()
        val made = setter.single { it.calls(BAR_ICON_FILTER) }
        assertEquals("the plain form", Opcode.INVOKE_STATIC, made.opcode)
        assertEquals("the color's register", 4, (made as FiveRegisterInstruction).registerC)
        assertEquals("its filter taken as before", Opcode.MOVE_RESULT_OBJECT, setter[setter.indexOf(made) + 1].opcode)
        assertTrue("the filter laid on as before", setter.any { it.callsMethod(IMAGE, "setColorFilter", FILTER) })

        val button = methods.getValue("A04").code()
        val ranged = button.single { it.calls(BAR_ICON_FILTER) }
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, ranged.opcode)
        assertEquals("the range's register", 17, (ranged as RegisterRangeInstruction).startRegister)

        val back = methods.getValue("A1d").code()
        val tint = back.single { it.calls(SET_BAR_ICON_COLOR) } as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, tint.opcode)
        assertEquals("the view", 1, tint.registerC)
        assertEquals("the color", 0, tint.registerD)
        assertTrue("no setColorFilter(int) left", back.none { it.callsMethod(IMAGE, "setColorFilter", "I") })

        assertTrue("another maker kept", methods.getValue("A1h").code().any { it.callsMethod(OTHER_MAKER, "A00", "I") })
        assertTrue("the text color kept", methods.getValue("A0m").code().any { it.callsMethod("Landroid/widget/TextView;", "setTextColor", "I") })
        assertTrue("no call to the bar's own maker left", methods.values.none { method -> method.code().any { it.callsMethod(FILTER_MAKER, "A00", "I") } })
        for ((name, stock) in actionBar().methods.associate { it.name to it.code() }) {
            assertEquals("$name: as many instructions", stock.size, methods.getValue(name).code().size)
        }

        for ((case, classes) in listOf(
            "no bar" to listOf(actionBar(message = false), filterMaker(), extension()),
            "a maker of another mode" to listOf(actionBar(tints = false), otherMaker(type = FILTER_MAKER), otherMaker(), extension()),
        )) {
            val untouched = PatchContexts.of(classes)
            untouched.tintActionBarIcons()
            val hooked = untouched.mutableClassDefBy(ACTION_BAR).methods.filter { method -> method.code().any { it.calls(BAR_ICON_FILTER) || it.calls(SET_BAR_ICON_COLOR) } }
            assertTrue("$case: no hook", hooked.isEmpty())
        }
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(actionBar(), filterMaker(), extension(bar = false))).tintActionBarIcons()
        }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * A profile tab hands the color it's told to the extension as setActiveColor(int) starts, in that
     * parameter's register, and the gray it reads for while it isn't selected right after reading it.
     */
    @Test
    fun aProfileTabsColorsGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(profileTab(), extension()))
        context.tintProfileTabs()
        val code = context.mutableClassDefBy(PROFILE_TAB).methods.single { it.name == "setActiveColor" }.code()
        val hooks = code.indices.filter { code[it].calls(BAR_ICONS) }
        assertEquals("two hooks", 2, hooks.size)
        assertEquals("the told color first", 0, hooks[0])
        assertEquals("in its parameter's register", 6, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("written back", 6, (code[1] as OneRegisterInstruction).registerA)
        val read = code.indexOfFirst { it.callsMethod(CONTEXT, "getColor", "I") }
        assertEquals("the gray right after it's taken", read + 2, hooks[1])
        assertEquals("the gray's register", 3, (code[hooks[1]] as RegisterRangeInstruction).startRegister)
        assertEquals("written back", 3, (code[hooks[1] + 1] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's code kept", profileTab().methods.single().code().map { it.opcode },
            code.filterIndexed { at, _ -> at !in hooks && at - 1 !in hooks }.map { it.opcode })

        PatchContexts.of(listOf(extension())).tintProfileTabs()
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(profileTab(), extension(bar = false))).tintProfileTabs() }
        assertTrue(failure.message!!.startsWith("Accent color"))
    }

    /**
     * A profile's Tagged tab hands its gray and its color to the extension right before the call making
     * its tint from them, at that call's label so the branch onto it runs the hooks too. The same call in
     * a method casting no badge is left alone, two such calls leave both as they are, and an extension
     * without the hook refuses.
     */
    @Test
    fun aProfilesTaggedTabColorsGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(taggedTab(), extension()))
        context.tintTaggedTab()
        val code = context.mutableClassDefBy(TAGGED_TABS).methods.single { it.name == "Apx" }.code()
        val hooks = code.indices.filter { code[it].calls(BAR_ICONS) }
        assertEquals("the gray and the color", 2, hooks.size)
        assertEquals("the gray's register", 5, (code[hooks[0]] as RegisterRangeInstruction).startRegister)
        assertEquals("written back", 5, (code[hooks[0] + 1] as OneRegisterInstruction).registerA)
        assertEquals("then the color's", 6, (code[hooks[1]] as RegisterRangeInstruction).startRegister)
        assertEquals("written back", 6, (code[hooks[1] + 1] as OneRegisterInstruction).registerA)
        assertEquals("right before the tint's made", hooks[1] + 2, code.indexOfFirst { it.callsStatic(COLORS, List(6) { "I" }) })
        val skip = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch onto the tint runs the hooks", hooks[0], skip.target.location.index)
        assertEquals("Instagram's code kept", taggedTab().methods.single().code().map { it.opcode },
            code.filterIndexed { at, _ -> at !in hooks && at - 1 !in hooks }.map { it.opcode })

        for ((case, classes) in listOf(
            "no badge cast" to listOf(taggedTab(casts = false)),
            "two tints" to listOf(taggedTab(tints = 2)),
        )) {
            val untouched = PatchContexts.of(classes + extension())
            untouched.tintTaggedTab()
            assertTrue("$case: no hook", untouched.mutableClassDefBy(TAGGED_TABS).methods.none { method -> method.code().any { it.calls(BAR_ICONS) } })
        }
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(taggedTab(), extension(bar = false))).tintTaggedTab() }
        assertTrue(failure.message!!.startsWith("Accent color"))
    }

    /**
     * A search bar names its own search icon to the extension right before drawing it, at that call's
     * label so the branch onto it does too, and hands over the color it draws its theme's icon in right
     * before that draw, written back. The icon wrapper both bars share hands over the default color it
     * reads right after the read, once. A bar drawing its own icon another way, or a wrapper reading its
     * color past a branch, leaves everything as it was, and an extension without the hooks refuses.
     */
    @Test
    fun aSearchBarsSearchIconGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(searchRow(), searchRow(SEARCH_TWIN), icons(), extension()))
        context.tintSearchBarIcons()
        for (type in listOf(SEARCH_ROW, SEARCH_TWIN)) {
            val code = context.mutableClassDefBy(type).methods.single { it.name == "A07" }.code()
            val named = code.indices.single { code[it].calls(SEARCH_ICON_NEXT) }
            assertTrue("$type: right before its own icon's drawn", code[named + 1].callsMethod(ICONS, "A1O", COMPOSER, MODIFIER, PAINTER, STRING))
            val skip = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
            assertEquals("$type: the branch onto that draw names it too", named, skip.target.location.index)
            val given = code.indices.single { code[it].calls(SEARCH_ICON) }
            assertEquals("$type: taken right before", Opcode.MOVE_RESULT_WIDE, code[given - 1].opcode)
            assertEquals("$type: the color's register", 18, (code[given] as RegisterRangeInstruction).startRegister)
            assertEquals("$type: both its halves", 2, (code[given] as RegisterRangeInstruction).registerCount)
            assertEquals("$type: written back", Opcode.MOVE_RESULT_WIDE, code[given + 1].opcode)
            assertEquals("$type: to its register", 18, (code[given + 1] as OneRegisterInstruction).registerA)
            assertTrue("$type: right before the theme's icon's drawn", code[given + 2].callsMethod(ICONS, "A0E", COMPOSER, MODIFIER, PAINTER, STRING, "J"))
            val hooks = setOf(named, given, given + 1)
            assertEquals("$type: Instagram's code kept", searchRow(type).methods.single().code().map { it.opcode },
                code.filterIndexed { at, _ -> at !in hooks }.map { it.opcode })
        }
        val wrapper = context.mutableClassDefBy(ICONS).methods.single { it.name == "A1O" }.code()
        val read = wrapper.indexOfFirst { it.opcode == Opcode.IGET_WIDE }
        assertEquals("the wrapper hooked once", 1, wrapper.count { it.calls(DEFAULT_ICON) })
        assertTrue("right after its read", wrapper[read + 1].calls(DEFAULT_ICON))
        assertEquals("the color's register", 5, (wrapper[read + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("written back", 5, (wrapper[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("Instagram's code kept", icons().methods.single().code().map { it.opcode },
            wrapper.filterIndexed { at, _ -> at != read + 1 && at != read + 2 }.map { it.opcode })

        for ((case, classes) in listOf(
            "no bar" to listOf(icons()),
            "a bar drawing its own icon another way" to listOf(searchRow(own = false), icons()),
            "a wrapper reading its color past a branch" to listOf(searchRow(), icons(branch = true)),
        )) {
            val untouched = PatchContexts.of(classes + extension())
            untouched.tintSearchBarIcons()
            val hooked = classes.flatMap { owner ->
                untouched.mutableClassDefBy(owner.type).methods.filter { method ->
                    method.code().any { it.calls(SEARCH_ICON_NEXT) || it.calls(SEARCH_ICON) || it.calls(DEFAULT_ICON) }
                }
            }
            assertTrue("$case: no hook", hooked.isEmpty())
        }
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(searchRow(), icons(), extension(bar = false))).tintSearchBarIcons() }
        assertTrue(failure.message!!.startsWith("Accent color"))
    }

    /**
     * The Search tab's top bar hands its Saved bookmark to the extension right after keeping it in its
     * field, and the hook hiding that bookmark still finds its check afterwards.
     */
    @Test
    fun theSearchTabsSavedBookmarkGoesThroughTheExtension() {
        val context = PatchContexts.of(listOf(exploreBar(), igImageView(), extension()))
        context.tintSearchSaved()
        val code = context.mutableClassDefBy(EXPLORE_ACTION_BAR).methods.single { it.name == "A0B" }.code()
        val store = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT }
        assertTrue("right after the store", code[store + 1].calls(SEARCH_SAVED))
        assertEquals("the bookmark's register", 1, (code[store + 1] as RegisterRangeInstruction).startRegister)
        context.findExploreSaved()

        val untouched = PatchContexts.of(listOf(exploreBar(stub = false), igImageView(), extension()))
        untouched.tintSearchSaved()
        assertTrue("no stub, no hook", untouched.mutableClassDefBy(EXPLORE_ACTION_BAR).methods.none { method -> method.code().any { it.calls(SEARCH_SAVED) } })
    }

    /**
     * Every build: one class loads the top bar's message, and every call it makes to its SRC_ATOP filter
     * maker (at least the back arrow's, the buttons' and the color setter's) and every
     * ImageView.setColorFilter(int) goes to the extension with the same registers. A profile tab hooks
     * its told color and its gray, and the Search tab's bookmark is handed over right after its store,
     * with the hook hiding it still able to read its check either way round. One tab maker casting the
     * badge hands Tagged's gray and color over right before making its tint.
     */
    @Test
    fun everyBuildHandsItsBarIconsToTheExtension() {
        for ((code, bundle) in builds()) {
            val bars = FixtureDex.classesLoading(bundle, ACTION_BAR_MESSAGE).distinctBy { it.type }
            assertEquals("$code: one top bar, ${bars.map { it.type }}", 1, bars.size)
            val bar = ImmutableClassDef.of(bars.single())
            val makers = bar.methods.flatMap { method ->
                method.code().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .filter { it.returnType == FILTER && it.parameterTypes.map(Any::toString) == listOf("I") }
            }.map { it.definingClass }.toSet()
            var next: Set<String> = setOf(PROFILE_TAB, EXPLORE_ACTION_BAR, "Lcom/instagram/common/ui/widget/imageview/IgImageView;")
            val held = mutableMapOf<String, ClassDef>()
            repeat(6) {
                val found = FixtureDex.classes(bundle, next - held.keys)
                held += found
                next = found.values.mapNotNull { it.superclass }.toSet()
            }
            held += FixtureDex.classes(bundle, makers)
            val badges = FixtureDex.classesLoading(bundle, TOASTING_BADGE_CAST).distinctBy { it.type }.filter { it.type != bar.type && it.type !in held.keys }
            val context = PatchContexts.of(listOf(bar, extension()) + held.values.filter { it.type != bar.type }.map { ImmutableClassDef.of(it) } +
                badges.map { ImmutableClassDef.of(it) })

            val stockCalls = bar.methods.sumOf { method -> method.code().count { it.callsStatic(FILTER, listOf("I")) && makesAtop(held[(it.called())!!.definingClass]) } }
            val stockTints = bar.methods.sumOf { method -> method.code().count { it.callsMethod(IMAGE, "setColorFilter", "I") } }
            assertTrue("$code: the bar makes its back arrow's, buttons' and setter's filters, $stockCalls", stockCalls >= 3)
            assertTrue("$code: the bar tints its back arrow, $stockTints", stockTints >= 1)
            context.tintBarIcons()

            val patched = context.mutableClassDefBy(bar.type).methods
            assertEquals("$code: every filter made through the extension", stockCalls, patched.sumOf { method -> method.code().count { it.calls(BAR_ICON_FILTER) } })
            assertEquals("$code: every back arrow tinted through it", stockTints, patched.sumOf { method -> method.code().count { it.calls(SET_BAR_ICON_COLOR) } })
            for (stock in bar.methods) {
                val after = patched.single { it.name == stock.name && it.parameterTypes.map(Any::toString) == stock.parameterTypes.map(Any::toString) }.code()
                val before = stock.code()
                assertEquals("$code: ${stock.name} as many instructions", before.size, after.size)
                for (at in before.indices) {
                    if (after[at].calls(BAR_ICON_FILTER)) {
                        assertEquals("$code: ${stock.name}@$at the color's register", before[at].firstRegister(), after[at].firstRegister())
                    } else if (after[at].calls(SET_BAR_ICON_COLOR)) {
                        assertTrue("$code: ${stock.name}@$at was the back arrow's tint", before[at].callsMethod(IMAGE, "setColorFilter", "I"))
                        assertEquals("$code: ${stock.name}@$at the view's register", before[at].firstRegister(), after[at].firstRegister())
                    } else {
                        assertEquals("$code: ${stock.name}@$at kept", before[at].opcode, after[at].opcode)
                    }
                }
            }

            val tab = context.mutableClassDefBy(PROFILE_TAB).methods.single { it.name == "setActiveColor" }.code()
            val tabHooks = tab.indices.filter { tab[it].calls(BAR_ICONS) }
            assertEquals("$code: the told color and the gray", 2, tabHooks.size)
            assertEquals("$code: the told color as it starts", 0, tabHooks[0])
            assertEquals("$code: the gray right after its read", Opcode.MOVE_RESULT, tab[tabHooks[1] - 1].opcode)
            assertEquals("$code: the gray's register", (tab[tabHooks[1] - 1] as OneRegisterInstruction).registerA,
                (tab[tabHooks[1]] as RegisterRangeInstruction).startRegister)

            val explore = context.mutableClassDefBy(EXPLORE_ACTION_BAR).methods.single { method -> method.code().any { it.loads(EXPLORE_SAVED_STUB) } }.code()
            val saved = explore.indices.single { explore[it].calls(SEARCH_SAVED) }
            val store = explore[saved - 1]
            assertEquals("$code: right after a store", Opcode.IPUT_OBJECT, store.opcode)
            assertEquals("$code: of the Search tab's bar", EXPLORE_ACTION_BAR, ((store as ReferenceInstruction).reference as FieldReference).definingClass)
            assertEquals("$code: the bookmark handed over", (store as TwoRegisterInstruction).registerA, (explore[saved] as RegisterRangeInstruction).startRegister)
            context.findExploreSaved()

            val tagged = badges.flatMap { owner -> context.mutableClassDefBy(owner.type).methods.filter { method -> method.code().any { it.calls(BAR_ICONS) } } }
            assertEquals("$code: one tab maker hands Tagged's colors over, ${tagged.map { "${it.definingClass}->${it.name}" }}", 1, tagged.size)
            val maker = tagged.single().code()
            val hooks = maker.indices.filter { maker[it].calls(BAR_ICONS) }
            assertEquals("$code: the gray and the color", 2, hooks.size)
            val made = maker[hooks[1] + 2]
            assertTrue("$code: right before the tint's made", made.callsStatic(COLORS, List(6) { "I" }))
            val gray = (made as RegisterRangeInstruction).startRegister
            assertEquals("$code: the gray's register", gray, (maker[hooks[0]] as RegisterRangeInstruction).startRegister)
            assertEquals("$code: written back", gray, (maker[hooks[0] + 1] as OneRegisterInstruction).registerA)
            assertEquals("$code: the color's register", gray + 1, (maker[hooks[1]] as RegisterRangeInstruction).startRegister)
            assertEquals("$code: written back", gray + 1, (maker[hooks[1] + 1] as OneRegisterInstruction).registerA)
            assertTrue("$code: laid on the tab's icon", maker[hooks[1] + 4].callsMethod(IMAGE, "setImageTintList", COLORS))
            val stock = badges.single { it.type == tagged.single().definingClass }.methods
                .single { it.name == tagged.single().name && it.parameterTypes.map(Any::toString) == tagged.single().parameterTypes.map(Any::toString) }
            assertEquals("$code: Instagram's code kept", stock.code().map { it.opcode }.filter { it != Opcode.NOP },
                maker.filterIndexed { at, _ -> at !in hooks && at - 1 !in hooks }.map { it.opcode }.filter { it != Opcode.NOP })
        }
    }

    /**
     * Every build: two static methods load both search icons, your messages' search bar and its twin.
     * Each names its own icon to the extension right before the wrapper draws it and hands over the
     * theme icon's color right before drawing that, and the one wrapper both use hands over the default
     * color right after reading it, with Instagram's code kept around the hooks.
     */
    @Test
    fun everyBuildHandsItsSearchBarIconsToTheExtension() {
        for ((code, bundle) in builds()) {
            val loading = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { method -> method.code().any { it.loads(INLINE_SEARCH_ICON) } }) loading += ImmutableClassDef.of(classDef)
                }
            }
            val bars = loading.flatMap { owner ->
                owner.methods.filter { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                        method.code().any { it.loads(INLINE_SEARCH_ICON) } && method.code().any { it.loads(SEARCH_OUTLINE_ICON) }
                }
            }
            assertEquals("$code: your messages' search bar and its twin, ${bars.map { "${it.definingClass}->${it.name}" }}", 2, bars.size)
            val drawers = bars.flatMap { bar ->
                bar.code().mapNotNull { it.called() }.filter { it.returnType == "V" && it.parameterTypes.size == 4 && it.parameterTypes.last().toString() == STRING }
            }.map { it.definingClass }.toSet()
            val context = PatchContexts.of(loading + FixtureDex.classes(bundle, drawers - loading.map { it.type }.toSet()).values + ExtensionDex.classDef(ACCENT_THEME))
            context.tintSearchBarIcons()

            val wrappers = mutableSetOf<String>()
            for (bar in bars) {
                val after = context.mutableClassDefBy(bar.definingClass).methods
                    .single { it.name == bar.name && it.parameterTypes.map(Any::toString) == bar.parameterTypes.map(Any::toString) }.code()
                val where = "$code: ${bar.definingClass}->${bar.name}"
                val named = after.indices.single { after[it].calls(SEARCH_ICON_NEXT) }
                val drawn = after[named + 1].called()!!
                assertTrue("$where: right before a static draw", after[named + 1].opcode == Opcode.INVOKE_STATIC || after[named + 1].opcode == Opcode.INVOKE_STATIC_RANGE)
                assertEquals("$where: of a painter named by a String", STRING, drawn.parameterTypes.last().toString())
                assertTrue("$where: after its own icon's load", after.subList(0, named).any { it.loads(SEARCH_OUTLINE_ICON) })
                wrappers += "${drawn.definingClass}->${drawn.name}(${drawn.parameterTypes.joinToString("")})${drawn.returnType}"

                val given = after.indices.single { after[it].calls(SEARCH_ICON) }
                assertEquals("$where: the color taken right before", Opcode.MOVE_RESULT_WIDE, after[given - 1].opcode)
                val color = (after[given - 1] as OneRegisterInstruction).registerA
                assertEquals("$where: the color's register", color, (after[given] as RegisterRangeInstruction).startRegister)
                assertEquals("$where: both its halves", 2, (after[given] as RegisterRangeInstruction).registerCount)
                assertEquals("$where: written back", Opcode.MOVE_RESULT_WIDE, after[given + 1].opcode)
                assertEquals("$where: to its register", color, (after[given + 1] as OneRegisterInstruction).registerA)
                val draw = after[given + 2] as RegisterRangeInstruction
                assertEquals("$where: drawn in that color", color, draw.startRegister + draw.registerCount - 2)
                assertEquals("$where: the color last", "J", after[given + 2].called()!!.parameterTypes.last().toString())
                assertTrue("$where: after the theme icon's load", after.subList(0, given).any { it.loads(INLINE_SEARCH_ICON) })

                val hooks = setOf(named, given, given + 1)
                assertEquals("$where: Instagram's code kept", bar.code().filter { it.opcode != Opcode.NOP }.map { it.kind() },
                    after.filterIndexed { at, instruction -> at !in hooks && instruction.opcode != Opcode.NOP }.map { it.kind() })
            }
            assertEquals("$code: one wrapper draws both bars' own icons, $wrappers", 1, wrappers.size)

            val shape = wrappers.single()
            val owner = shape.substringBefore("->")
            val icon = context.mutableClassDefBy(owner).methods.single { method ->
                "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" == shape
            }.code()
            val hook = icon.indices.single { icon[it].calls(DEFAULT_ICON) }
            assertEquals("$code: right after reading the default color", Opcode.IGET_WIDE, icon[hook - 1].opcode)
            val read = (icon[hook - 1] as TwoRegisterInstruction).registerA
            assertEquals("$code: the color's register", read, (icon[hook] as RegisterRangeInstruction).startRegister)
            assertEquals("$code: written back", read, (icon[hook + 1] as OneRegisterInstruction).registerA)
            assertTrue("$code: nothing branches before the read", icon.subList(0, hook - 1).none { it is OffsetInstruction })
            val stock = FixtureDex.classes(bundle, setOf(owner)).getValue(owner).methods.single { method ->
                "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" == shape
            }
            assertEquals("$code: the wrapper's code kept", stock.code().filter { it.opcode != Opcode.NOP }.map { it.kind() },
                icon.filterIndexed { at, instruction -> at != hook && at != hook + 1 && instruction.opcode != Opcode.NOP }.map { it.kind() })
        }
    }

    /**
     * Each filter an opened chat's header makes with the SRC_ATOP maker and lays straight on an image
     * view comes from the extension's maker instead, with the same register and form. A filter laid on
     * a drawable and another maker's keep Instagram's code. No header, two headers or a header laying no
     * such filter is left as it is, and an extension without the hook refuses.
     */
    @Test
    fun aChatHeadersIconsGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(chatHeader(), filterMaker(), otherMaker(), extension()))
        context.tintChatHeaderIcons()
        val methods = context.mutableClassDefBy(CHAT_HEADER_VIEW).methods.associateBy { it.name }

        val back = methods.getValue("A0A").code()
        val made = back.single { it.calls(BAR_ICON_FILTER) }
        assertEquals("the plain form", Opcode.INVOKE_STATIC, made.opcode)
        assertEquals("the color's register", 4, (made as FiveRegisterInstruction).registerC)
        assertEquals("its filter taken as before", Opcode.MOVE_RESULT_OBJECT, back[back.indexOf(made) + 1].opcode)
        assertTrue("laid on the icon as before", back[back.indexOf(made) + 2].callsMethod(IMAGE, "setColorFilter", FILTER))
        assertTrue("another maker kept", back.any { it.callsMethod(OTHER_MAKER, "A00", "I") })
        assertEquals("the filter laid on a drawable kept", 1, back.count { it.callsMethod(FILTER_MAKER, "A00", "I") })
        assertTrue("that drawable's filter right before it", back[back.indexOfFirst { it.callsMethod(FILTER_MAKER, "A00", "I") } + 2].callsMethod(DRAWABLE, "setColorFilter", FILTER))

        val button = methods.getValue("A05").code()
        val ranged = button.single { it.calls(BAR_ICON_FILTER) }
        assertEquals("the range form", Opcode.INVOKE_STATIC_RANGE, ranged.opcode)
        assertEquals("the range's register", 16, (ranged as RegisterRangeInstruction).startRegister)
        assertEquals("one register", 1, ranged.registerCount)
        for ((name, stock) in chatHeader().methods.associate { it.name to it.code() }) {
            assertEquals("$name: Instagram's code kept", stock.map { it.opcode }, methods.getValue(name).code().map { it.opcode })
        }

        for ((case, classes) in listOf(
            "no header" to listOf(chatHeader(trace = false), filterMaker(), extension()),
            "two headers" to listOf(chatHeader(), chatHeader(type = OTHER_HEADER), filterMaker(), extension()),
            "no filter on an icon" to listOf(chatHeader(icons = false), filterMaker(), extension()),
        )) {
            val untouched = PatchContexts.of(classes)
            untouched.tintChatHeaderIcons()
            val hooked = classes.filter { it.type == CHAT_HEADER_VIEW || it.type == OTHER_HEADER }.flatMap { untouched.mutableClassDefBy(it.type).methods }
                .filter { method -> method.code().any { it.calls(BAR_ICON_FILTER) } }
            assertTrue("$case: no hook", hooked.isEmpty())
        }
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(listOf(chatHeader(), filterMaker(), extension(bar = false))).tintChatHeaderIcons()
        }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * The tab icon view hands the color it's told to the extension with itself as setNormalColor(int)
     * and setActiveColor(int) start, in the range form, and takes the answer back into that parameter,
     * with Instagram's code kept after it. Its method handing both on keeps its code. No view, or a view
     * missing either setter, is left as it is, and an extension without the hook refuses.
     */
    @Test
    fun aChatComposersButtonsGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(colorView(), extension()))
        context.tintComposerIcons()
        val methods = context.mutableClassDefBy(TAB_ICON_VIEW).methods.associateBy { it.name }
        for (name in listOf("setNormalColor", "setActiveColor")) {
            val code = methods.getValue(name).code()
            assertTrue("$name: the hook first", code[0].calls(COMPOSER_ICON))
            assertEquals("$name: the range form", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
            assertEquals("$name: the view and its color", listOf(2, 2), (code[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
            assertEquals("$name: written back", Opcode.MOVE_RESULT, code[1].opcode)
            assertEquals("$name: into the color", 3, (code[1] as OneRegisterInstruction).registerA)
            val stock = colorView().methods.single { it.name == name }.code()
            assertEquals("$name: Instagram's code kept", stock.map { it.opcode }, code.drop(2).map { it.opcode })
        }
        val both = methods.getValue("A03").code()
        assertEquals("the method handing both on kept", colorView().methods.single { it.name == "A03" }.code().map { it.opcode }, both.map { it.opcode })

        PatchContexts.of(listOf(extension())).tintComposerIcons()
        val missing = PatchContexts.of(listOf(colorView(active = false), extension()))
        missing.tintComposerIcons()
        assertTrue("a missing setter: no hook", missing.mutableClassDefBy(TAB_ICON_VIEW).methods.none { method -> method.code().any { it.calls(COMPOSER_ICON) } })
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(colorView(), extension(bar = false))).tintComposerIcons() }
        assertTrue("no hook in the extension: ${failure.message}", failure.message!!.startsWith("Accent color"))
    }

    /**
     * Every build: the one class coloring an opened chat's header lays two filters from the SRC_ATOP
     * maker straight on image views, its back arrow's and its buttons', and both come from the
     * extension's maker in the same registers and form. The tab icon view's two color setters hand
     * their color over first, with Instagram's code kept after.
     */
    @Test
    fun everyBuildHandsItsChatHeaderAndComposerIconsToTheExtension() {
        for ((code, bundle) in builds()) {
            val loading = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { method -> method.code().any { it.loads(CHAT_HEADER_TRACE.toInt()) } }) loading += ImmutableClassDef.of(classDef)
                }
            }
            val makers = loading.flatMap { owner -> owner.methods.flatMap { it.code() } }
                .filter { it.callsStatic(FILTER, listOf("I")) }.map { it.called()!!.definingClass }.toSet()
            val others = FixtureDex.classes(bundle, makers + TAB_ICON_VIEW - loading.map { it.type }.toSet()).values
            val context = PatchContexts.of(loading + others + ExtensionDex.classDef(ACCENT_THEME))
            val header = context.chatHeaderClasses().single()
            val stock = loading.single { it.type == header }
            context.tintChatHeaderIcons()
            context.tintComposerIcons()

            var hooked = 0
            for (method in context.mutableClassDefBy(header).methods) {
                val after = method.code()
                val before = stock.methods.single { it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) }.code()
                val where = "$code: $header->${method.name}"
                assertEquals("$where: Instagram's code kept", before.filter { it.opcode != Opcode.NOP }.map { it.kind() }, after.filter { it.opcode != Opcode.NOP }.map { it.kind() })
                for (at in after.indices.filter { after[it].calls(BAR_ICON_FILTER) }) {
                    hooked++
                    assertEquals("$where@$at: in the maker's register", before[at].firstRegister(), after[at].firstRegister())
                    assertTrue("$where@$at: in place of a SRC_ATOP maker", makesAtop(others.singleOrNull { it.type == before[at].called()!!.definingClass }))
                    assertEquals("$where@$at: its filter taken", Opcode.MOVE_RESULT_OBJECT, after[at + 1].opcode)
                    assertTrue("$where@$at: laid on an image view", after[at + 2].callsMethod(IMAGE, "setColorFilter", FILTER))
                }
            }
            assertEquals("$code: the back arrow's and the buttons' filters", 2, hooked)

            val view = context.mutableClassDefBy(TAB_ICON_VIEW)
            val stockView = others.single { it.type == TAB_ICON_VIEW }
            for (name in listOf("setNormalColor", "setActiveColor")) {
                val setter = view.methods.single { it.name == name && it.parameterTypes.map(Any::toString) == listOf("I") }
                val after = setter.code()
                val registers = setter.implementation!!.registerCount
                val where = "$code: $name"
                assertTrue("$where: the hook first", after[0].calls(COMPOSER_ICON))
                assertEquals("$where: the view and its color", listOf(registers - 2, 2), (after[0] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) })
                assertEquals("$where: written back into the color", registers - 1, (after[1] as OneRegisterInstruction).registerA)
                val before = stockView.methods.single { it.name == name && it.parameterTypes.map(Any::toString) == listOf("I") }.code()
                assertEquals("$where: Instagram's code kept", before.filter { it.opcode != Opcode.NOP }.map { it.kind() }, after.drop(2).filter { it.opcode != Opcode.NOP }.map { it.kind() })
            }
        }
    }

    private fun builds(): Map<String, File> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val declared = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        return (declared + Fixtures.otherBuilds()).associateBy { file ->
            (if (file.extension == "apks") file.nameWithoutExtension else file.parentFile.name).substringAfterLast('-')
        }.toSortedMap()
    }

    private fun makesAtop(maker: ClassDef?): Boolean = maker?.methods?.any { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == FILTER && method.code().any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "SRC_ATOP"
        }
    } == true

    private companion object {
        const val CONTEXT = "Landroid/content/Context;"
        const val FILTER = "Landroid/graphics/ColorFilter;"
        const val IMAGE = "Landroid/widget/ImageView;"
        const val ACTION_BAR = "Lfixture/ActionBar;"
        const val FILTER_MAKER = "Lfixture/BarFilters;"
        const val OTHER_MAKER = "Lfixture/OtherFilters;"
        const val IG_IMAGE = "Lfixture/IgImageView;"
        const val COLORS = "Landroid/content/res/ColorStateList;"
        const val TAGGED_TABS = "Lfixture/ProfileTabs;"
        const val TINTS = "Lfixture/Tints;"
        const val STRING = "Ljava/lang/String;"
        const val COMPOSER = "Lfixture/Composer;"
        const val MODIFIER = "Lfixture/Modifier;"
        const val PAINTER = "Lfixture/Painter;"
        const val PAINTERS = "Lfixture/Painters;"
        const val PALETTE = "Lfixture/Palette;"
        const val PALETTE_COLORS = "Lfixture/PaletteColors;"
        const val ICONS = "Lfixture/Icons;"
        const val SEARCH_ROW = "Lfixture/SearchRow;"
        const val SEARCH_TWIN = "Lfixture/SearchTwin;"
        const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
        const val CHAT_HEADER_VIEW = "Lfixture/ChatHeader;"
        const val OTHER_HEADER = "Lfixture/OtherHeader;"
        const val CHAT_THEME = "Lfixture/ChatTheme;"

        /**
         * An opened chat's header as 386314286's `LX/08Cr` has it: A0A loads the header's trace key
         * (unless not [trace]) into a static (View, int, int) call coloring the header, lays a filter
         * from the SRC_ATOP maker on its back arrow, another maker's on it too and the SRC_ATOP maker's
         * on a drawable; A05 lays one on a button with the color in v16 through the range form. Without
         * [icons], both lay theirs on drawables only.
         */
        fun chatHeader(type: String = CHAT_HEADER_VIEW, trace: Boolean = true, icons: Boolean = true): ClassDef {
            val lay = if (icons) IMAGE else DRAWABLE
            return classOf(
                type,
                method(type, "A0A", listOf(CHAT_THEME), 6, """
                    const v0, ${if (trace) "0x14d400c4" else "0x14d400c5"}
                    iget-object v1, p0, $type->A06:Landroid/view/View;
                    const/4 v2, 0x0
                    invoke-static { v1, v2, v0 }, Lfixture/Views;->A00(Landroid/view/View;II)V
                    iget-object v3, p0, $type->A09:$lay
                    iget v4, p1, $CHAT_THEME->A02:I
                    invoke-static { v4 }, $FILTER_MAKER->A00(I)$FILTER
                    move-result-object v5
                    invoke-virtual { v3, v5 }, $lay->setColorFilter($FILTER)V
                    invoke-static { v4 }, $OTHER_MAKER->A00(I)$FILTER
                    move-result-object v5
                    invoke-virtual { v3, v5 }, $lay->setColorFilter($FILTER)V
                    iget-object v2, p0, $type->A0B:$DRAWABLE
                    invoke-static { v4 }, $FILTER_MAKER->A00(I)$FILTER
                    move-result-object v5
                    invoke-virtual { v2, v5 }, $DRAWABLE->setColorFilter($FILTER)V
                    return-void
                """, static = false),
                method(type, "A05", listOf("Landroid/view/View;", "Lfixture/Row;", "I"), 17, """
                    move/from16 v16, p3
                    move-object/from16 v1, p0
                    iget-object v0, v1, $type->A0C:$lay
                    invoke-static/range { v16 .. v16 }, $FILTER_MAKER->A00(I)$FILTER
                    move-result-object v2
                    invoke-virtual { v0, v2 }, $lay->setColorFilter($FILTER)V
                    return-void
                """, static = false),
            )
        }

        /**
         * Instagram's tab icon view as 457 has it: setNormalColor(int) and setActiveColor(int) keep the
         * color boxed, and A03 hands both on. Without [active], no setActiveColor.
         */
        fun colorView(active: Boolean = true): ClassDef {
            fun setter(name: String, field: String) = method(TAB_ICON_VIEW, name, listOf("I"), 2, """
                invoke-static { p1 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v1
                iput-object v1, p0, $TAB_ICON_VIEW->$field:Ljava/lang/Integer;
                return-void
            """, static = false)
            val methods = listOfNotNull(
                setter("setNormalColor", "A05"),
                if (active) setter("setActiveColor", "A04") else null,
                method(TAB_ICON_VIEW, "A03", listOf("I", "I"), 0, """
                    invoke-virtual { p0, p1 }, $TAB_ICON_VIEW->setNormalColor(I)V
                    invoke-virtual { p0, p2 }, $TAB_ICON_VIEW->setActiveColor(I)V
                    return-void
                """, static = false),
            )
            return classOf(TAB_ICON_VIEW, *methods.toTypedArray(), superclass = IMAGE)
        }

        /**
         * The top bar as 457's `LX/01pF` has it: the message only it throws, A1P making a filter of the
         * color it's given and laying it on its back arrow, A04 making one for a button with the range
         * form, A1d tinting its back arrow with its color, A1h making one with another maker and A0m
         * coloring its title. Without [message], no message; without [tints], no A1d.
         */
        fun actionBar(message: Boolean = true, tints: Boolean = true): ClassDef {
            val methods = listOfNotNull(
                if (message) method(ACTION_BAR, "A00", emptyList(), 1, "const-string v0, \"$ACTION_BAR_MESSAGE\"\nreturn-void", static = false) else null,
                method(ACTION_BAR, "A1P", listOf("I"), 3, """
                    invoke-static { p1 }, $FILTER_MAKER->A00(I)$FILTER
                    move-result-object v1
                    iget-object v0, p0, $ACTION_BAR->A0A:$IMAGE
                    invoke-virtual { v0, v1 }, $IMAGE->setColorFilter($FILTER)V
                    return-void
                """, static = false),
                method(ACTION_BAR, "A04", emptyList(), 18, """
                    move-object/from16 v1, p0
                    iget v0, v1, $ACTION_BAR->A01:I
                    move/from16 v17, v0
                    invoke-static/range { v17 .. v17 }, $FILTER_MAKER->A00(I)$FILTER
                    move-result-object v0
                    return-void
                """, static = false),
                if (tints) method(ACTION_BAR, "A1d", emptyList(), 2, """
                    iget-object v1, p0, $ACTION_BAR->A0A:$IMAGE
                    iget v0, p0, $ACTION_BAR->A01:I
                    invoke-virtual { v1, v0 }, $IMAGE->setColorFilter(I)V
                    return-void
                """, static = false) else null,
                method(ACTION_BAR, "A1h", listOf("I"), 1, """
                    invoke-static { p1 }, $OTHER_MAKER->A00(I)$FILTER
                    move-result-object v0
                    return-void
                """, static = false),
                method(ACTION_BAR, "A0m", listOf("Landroid/widget/TextView;", "I"), 0, """
                    invoke-virtual { p1, p2 }, Landroid/widget/TextView;->setTextColor(I)V
                    return-void
                """, static = false),
            )
            return classOf(ACTION_BAR, *methods.toTypedArray())
        }

        /** The top bar's filter maker as `LX/03fF;->A00` has it, one PorterDuffColorFilter in SRC_ATOP mode per color. */
        fun filterMaker(): ClassDef = classOf(FILTER_MAKER, method(FILTER_MAKER, "A00", listOf("I"), 2, """
            sget-object v1, Landroid/graphics/PorterDuff${'$'}Mode;->SRC_ATOP:Landroid/graphics/PorterDuff${'$'}Mode;
            new-instance v0, Landroid/graphics/PorterDuffColorFilter;
            invoke-direct { v0, p0, v1 }, Landroid/graphics/PorterDuffColorFilter;-><init>(ILandroid/graphics/PorterDuff${'$'}Mode;)V
            return-object v0
        """, static = true, returns = FILTER))

        /** A `(int)ColorFilter` maker of another kind, a LightingColorFilter. */
        fun otherMaker(type: String = OTHER_MAKER): ClassDef = classOf(type, method(type, "A00", listOf("I"), 2, """
            const/4 v1, 0x0
            new-instance v0, Landroid/graphics/LightingColorFilter;
            invoke-direct { v0, p0, v1 }, Landroid/graphics/LightingColorFilter;-><init>(II)V
            return-object v0
        """, static = true, returns = FILTER))

        /** A profile tab's setActiveColor(int) as 457 has it: the told color kept and the secondary icon gray read for the idle state. */
        fun profileTab(): ClassDef = classOf(PROFILE_TAB, method(PROFILE_TAB, "setActiveColor", listOf("I"), 5, """
            iput p1, p0, $PROFILE_TAB->A00:I
            invoke-virtual { p0 }, Landroid/view/View;->getContext()$CONTEXT
            move-result-object v3
            const v0, 0x7f040897
            invoke-virtual { v3, v0 }, $CONTEXT->getColor(I)I
            move-result v3
            filled-new-array { p1, v3 }, [I
            move-result-object v0
            return-void
        """, static = false))

        /**
         * The Search tab's top bar as 457 has it: it reads its bookmark's field, and when that's null finds
         * the stub, inflates it and keeps it. Without [stub], it loads another id.
         */
        fun exploreBar(stub: Boolean = true): ClassDef = classOf(EXPLORE_ACTION_BAR, method(EXPLORE_ACTION_BAR, "A0B", emptyList(), 3, """
            iget-object v2, p0, $EXPLORE_ACTION_BAR->A06:$IG_IMAGE
            if-nez v2, :done
            const v2, ${if (stub) EXPLORE_SAVED_STUB else 0x7f0b0001}
            invoke-virtual { p0, v2 }, Landroid/view/View;->findViewById(I)Landroid/view/View;
            move-result-object v1
            check-cast v1, $IG_IMAGE
            iput-object v1, p0, $EXPLORE_ACTION_BAR->A06:$IG_IMAGE
            const v0, 0x7f0812d7
            :done
            return-void
        """, static = false), superclass = "Landroid/widget/FrameLayout;")

        fun igImageView(): ClassDef = classOf(IG_IMAGE, superclass = IMAGE)

        /**
         * A profile's tab maker as 386314286's `LX/0Bux;->Apx` has it: it throws [TOASTING_BADGE_CAST]
         * and casts the badge it inflates unless not [casts], then makes the tint from the gray in v5 and
         * the color handed in as p3 in v6, [tints] times, a branch landing on the first, and lays it on.
         */
        fun taggedTab(casts: Boolean = true, tints: Int = 1): ClassDef = classOf(TAGGED_TABS, method(TAGGED_TABS, "Apx", listOf("Landroid/view/ViewGroup;", "Ljava/lang/String;", "I"), 11, (listOf(
            "const-string v0, \"$TOASTING_BADGE_CAST\"",
            "const/4 v1, 0x0",
            if (casts) "check-cast v1, $TOASTING_BADGE" else "nop",
            "const v5, 0x7f040897",
            "move v6, p3",
            "const/4 v7, 0x0",
            "const/4 v8, 0x0",
            "const/4 v9, 0x0",
            "const/4 v10, 0x0",
            "const/4 v2, 0x0",
            "if-eqz p3, :tint",
            "nop",
            ":tint",
        ) + List(tints) {
            """
            invoke-static/range { v5 .. v10 }, $TINTS->A01(IIIIII)$COLORS
            move-result-object v0
            invoke-virtual { v2, v0 }, $IMAGE->setImageTintList($COLORS)V
            """.trimIndent()
        } + listOf("return-object v1")).joinToString("\n"), static = false, returns = "Landroid/view/View;"))

        /**
         * A Compose search bar as 386314286's `LX/0O2s;->A07` has it: it loads its own search outline,
         * makes a painter of it and draws it with the icon wrapper (a branch landing on that draw), or
         * unless [own] hands the painter elsewhere; then loads the icon its theme names and draws it with
         * the color it reads, taken right before the range call.
         */
        fun searchRow(type: String = SEARCH_ROW, own: Boolean = true): ClassDef = classOf(type, method(type, "A07", listOf(COMPOSER, MODIFIER), 20, listOf(
            "move-object/from16 v6, p0",
            "move-object/from16 v7, p1",
            "const v1, $SEARCH_OUTLINE_ICON",
            "invoke-static { v1, v6 }, $PAINTERS->A00(I$COMPOSER)$PAINTER",
            "move-result-object v2",
            "const-string v3, \"Search\"",
            "if-eqz v2, :named",
            "nop",
            ":named",
            if (own) "invoke-static { v6, v7, v2, v3 }, $ICONS->A1O($COMPOSER$MODIFIER$PAINTER$STRING)V" else "invoke-virtual { v6, v2 }, $COMPOSER->A00($PAINTER)V",
            "const v4, $INLINE_SEARCH_ICON",
            "invoke-static { v4, v6 }, $PAINTERS->A00(I$COMPOSER)$PAINTER",
            "move-result-object v5",
            "move-object/from16 v14, v6",
            "move-object/from16 v15, v7",
            "move-object/from16 v16, v5",
            "move-object/from16 v17, v3",
            "invoke-static { v6 }, $PALETTE->A0D($COMPOSER)J",
            "move-result-wide v18",
            "invoke-static/range { v14 .. v19 }, $ICONS->A0E($COMPOSER$MODIFIER$PAINTER${STRING}J)V",
            "return-void",
        ).joinToString("\n"), static = true))

        /**
         * Compose's icon wrapper as 386314286's `LX/0833;->A1O` has it: it reads the default color off
         * the composer's palette and draws the painter in it, with [branch] a branch before the read.
         */
        fun icons(branch: Boolean = false): ClassDef = classOf(ICONS, method(ICONS, "A1O", listOf(COMPOSER, MODIFIER, PAINTER, STRING), 6, (listOf(
            "move-object v1, p0",
            "invoke-static { p0 }, $PALETTE->A00($COMPOSER)$PALETTE_COLORS",
            "move-result-object v0",
        ) + (if (branch) listOf("if-eqz v0, :read", "nop", ":read") else emptyList()) + listOf(
            "iget-wide v5, v0, $PALETTE_COLORS->A10:J",
            "move-object v2, p1",
            "move-object v3, p2",
            "move-object v4, p3",
            "invoke-static/range { v1 .. v6 }, $ICONS->A0E($COMPOSER$MODIFIER$PAINTER${STRING}J)V",
            "return-void",
        )).joinToString("\n"), static = true))

        /** AccentTheme's bar hooks, without them when [bar] is false. */
        fun extension(bar: Boolean = true): ClassDef = classOf(
            ACCENT_THEME,
            *listOfNotNull(
                method(ACCENT_THEME, "apply", listOf(CONTEXT), 0, "return-void", static = true),
                if (bar) method(ACCENT_THEME, "barIcons", listOf("I"), 0, "return p0", static = true, returns = "I") else null,
                if (bar) method(ACCENT_THEME, "barIconFilter", listOf("I"), 1, "const/4 v0, 0x0\nreturn-object v0", static = true, returns = FILTER) else null,
                if (bar) method(ACCENT_THEME, "setBarIconColor", listOf(IMAGE, "I"), 0, "return-void", static = true) else null,
                if (bar) method(ACCENT_THEME, "searchSaved", listOf(IMAGE), 0, "return-void", static = true) else null,
                if (bar) method(ACCENT_THEME, "searchIconNext", emptyList(), 0, "return-void", static = true) else null,
                if (bar) method(ACCENT_THEME, "defaultIcon", listOf("J"), 1, "return-wide p0", static = true, returns = "J") else null,
                if (bar) method(ACCENT_THEME, "searchIcon", listOf("J"), 1, "return-wide p0", static = true, returns = "J") else null,
                if (bar) method(ACCENT_THEME, "composerIcon", listOf("Landroid/view/View;", "I"), 0, "return p1", static = true, returns = "I") else null,
            ).toTypedArray(),
        )

        fun method(type: String, name: String, parameters: List<String>, locals: Int, body: String, static: Boolean, returns: String = "V"): Method {
            val registers = locals + parameters.size + if (static) 0 else 1
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().trim()) },
            )
        }

        fun classOf(type: String, vararg methods: Method, superclass: String = "Ljava/lang/Object;"): ClassDef = ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods.toList(),
        )
    }
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.called(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.calls(hook: String): Boolean = called()?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook
} == true

private fun Instruction.callsMethod(owner: String, name: String, vararg parameters: String): Boolean = called()?.let {
    it.definingClass == owner && it.name == name && it.parameterTypes.map(Any::toString) == parameters.toList()
} == true

private fun Instruction.callsStatic(returns: String, parameters: List<String>): Boolean =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) && called()?.let {
        it.returnType == returns && it.parameterTypes.map(Any::toString) == parameters
    } == true

private fun Instruction.firstRegister(): Int = when (this) {
    is RegisterRangeInstruction -> startRegister
    is FiveRegisterInstruction -> registerC
    else -> -1
}

private fun Instruction.loads(literal: Int) = (this as? NarrowLiteralInstruction)?.narrowLiteral == literal

private fun Instruction.kind(): Opcode = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

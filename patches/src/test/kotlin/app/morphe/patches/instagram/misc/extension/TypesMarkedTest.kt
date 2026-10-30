/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [typesMarked] answers from an index of the app's purge markers each context builds once (#173). A
 * name has to find every class whose marker ends in it, part by part, and nothing else; a class the
 * patcher merges in after the first ask has to be found on the next; and one context's answer must
 * never come back for another.
 */
class TypesMarkedTest {
    @Test
    fun aNameFindsTheClassesWhoseMarkerEndsInItAndNoOthers() {
        val context = PatchContexts.of(classes())
        assertEquals(setOf(MENU, SECOND_MENU), context.typesMarked("hasAiDiscoveryMenu"))
        assertEquals(setOf(HANDLER), context.typesMarked("handleDoubleTapMedia"))
        assertEquals(setOf(HANDLER), context.typesMarked("GestureActionHandler_handleDoubleTapMedia"))
        assertEquals(setOf(MENU, SECOND_MENU, HANDLER), context.typesMarked("hasAiDiscoveryMenu", "handleDoubleTapMedia"))
        // Only part of a name, a name the marker doesn't end in, or a string that isn't a marker.
        assertEquals(emptySet<String>(), context.typesMarked("DiscoveryMenu"))
        assertEquals(emptySet<String>(), context.typesMarked("GestureActionHandler"))
        assertEquals(emptySet<String>(), context.typesMarked("settingsMenu"))
    }

    @Test
    fun anotherContextNeverGetsThisOnesAnswer() {
        val first = PatchContexts.of(classes())
        assertEquals(setOf(MENU, SECOND_MENU), first.typesMarked("hasAiDiscoveryMenu"))
        val second = PatchContexts.of(listOf(marked(OTHER, "android_purge_26_q2_OtherMenu_hasAiDiscoveryMenu")))
        assertEquals(setOf(OTHER), second.typesMarked("hasAiDiscoveryMenu"))
        assertEquals(emptySet<String>(), PatchContexts.of(listOf(marked(OTHER, "plain"))).typesMarked("hasAiDiscoveryMenu"))
        assertEquals(setOf(MENU, SECOND_MENU), first.typesMarked("hasAiDiscoveryMenu"))
    }

    @Test
    fun aClassMergedInAfterTheFirstAskIsFoundOnTheNext() {
        val context = PatchContexts.of(listOf(marked(HANDLER, "android_purge_26_q2_GestureActionHandler_handleDoubleTapMedia"), marked(OTHER, "plain")))
        assertEquals(emptySet<String>(), context.typesMarked("hasAiDiscoveryMenu"))
        PatchContexts.add(context, marked(MENU, "android_purge_26_q3_ClipsMenu_hasAiDiscoveryMenu"))
        assertEquals(setOf(MENU), context.typesMarked("hasAiDiscoveryMenu"))
        // A merge that replaces a class of the app with one holding a marker.
        val replacement = "android_purge_26_q2_OtherMenu_hasAiDiscoveryMenu"
        PatchContexts.add(context, marked(OTHER, replacement))
        assertEquals(setOf(OTHER), context.classDefByStrings(replacement).map { it.type }.toSet())
        assertEquals(setOf(OTHER), context.classesHolding(replacement).map { it.type }.toSet())
        assertEquals(setOf(MENU, OTHER), context.typesMarked("hasAiDiscoveryMenu"))
    }

    @Test
    fun replacingAMarkerWithAnotherRetiresItsOldName() {
        val context = PatchContexts.of(listOf(marked(MENU, "android_purge_26_q3_Menu_old"), marked(OTHER, "plain")))
        assertEquals(setOf(MENU), context.typesMarked("old"))
        PatchContexts.add(context, marked(MENU, "android_purge_26_q3_Menu_new"))
        assertEquals(emptySet<String>(), context.typesMarked("old"))
        assertEquals(setOf(MENU), context.typesMarked("new"))
        PatchContexts.add(context, marked(MENU, "plain"))
        assertEquals(emptySet<String>(), context.typesMarked("old", "new"))
    }

    @Test
    fun equalStringHolderCountsDoNotHideMembershipAndOrderChanges() {
        val context = PatchContexts.of(listOf(
            marked(MENU, "android_purge_26_q3_Menu_old"), marked(OTHER, "plain"), marked(PLAIN),
        ))
        assertEquals(setOf(MENU), context.typesMarked("old"))
        val count = context.getAllClassesWithStrings().size
        PatchContexts.add(context, marked(MENU))
        PatchContexts.add(context, marked(PLAIN, "android_purge_26_q3_Other_new"))
        assertEquals(count, context.getAllClassesWithStrings().size)
        assertEquals(emptySet<String>(), context.typesMarked("old"))
        assertEquals(setOf(PLAIN), context.typesMarked("new"))
    }

    @Test
    fun makingAnEarlierClassMutableKeepsItsMarkers() {
        val context = PatchContexts.of(listOf(
            marked(MENU, "android_purge_26_q3_Menu_first"), marked(OTHER, "android_purge_26_q3_Other_last"),
        ))
        assertEquals(setOf(MENU), context.typesMarked("first"))
        context.mutableClassDefBy(MENU)
        assertEquals(setOf(MENU), context.typesMarked("first"))
        assertEquals(setOf(OTHER), context.typesMarked("last"))
    }

    @Test
    fun unchangedAsksDoNotReadClassTypesOrMethodBodiesAgain() {
        val counted = Counted(marked(MENU, "android_purge_26_q3_Menu_first"))
        val plain = Counted(marked(OTHER, "plain"))
        val context = PatchContexts.of(listOf(counted, plain))
        assertEquals(setOf(MENU), context.typesMarked("first"))
        counted.typeReads = 0; counted.methodReads = 0
        plain.typeReads = 0; plain.methodReads = 0
        repeat(5) {
            assertEquals(setOf(MENU), context.typesMarked("first"))
            assertEquals(emptySet<String>(), context.typesMarked("absent"))
        }
        assertEquals(0, counted.typeReads); assertEquals(0, counted.methodReads)
        assertEquals(0, plain.typeReads); assertEquals(0, plain.methodReads)
    }

    private class Counted(private val source: ClassDef) : ClassDef by source {
        var typeReads = 0
        var methodReads = 0
        override fun getType(): String { typeReads++; return source.type }
        override fun getMethods(): Iterable<Method> { methodReads++; return source.methods }
    }

    private companion object {
        const val MENU = "Lfixture/Menu;"
        const val SECOND_MENU = "Lfixture/SecondMenu;"
        const val HANDLER = "Lfixture/Handler;"
        const val PARTIAL = "Lfixture/Partial;"
        const val PLAIN = "Lfixture/Plain;"
        const val OTHER = "Lfixture/Other;"
        const val EXTENSION = "Lapp/hushgram/extension/Marked;"

        fun marked(type: String, vararg strings: String): ClassDef {
            val method = MutableMethod(
                ImmutableMethod(
                    type, "run", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(1, emptyList(), null, null),
                ),
            )
            method.addInstructions(0, strings.joinToString("\n") { "const-string v0, \"$it\"" } + "\nreturn-void")
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(ImmutableMethod.of(method)))
        }

        fun classes(): List<ClassDef> = listOf(
            marked(MENU, "android_purge_26_q3_ClipsMenu_hasAiDiscoveryMenu"),
            marked(SECOND_MENU, "unrelated", "android_purge_26_q2_ReelMenu_hasAiDiscoveryMenu"),
            marked(HANDLER, "android_purge_26_q2_GestureActionHandler_handleDoubleTapMedia"),
            // Ends in the name's letters, but not after an underscore.
            marked(PARTIAL, "android_purge_26_q3_Clips_nothasAiDiscoveryMenu"),
            // Ends in the name, but isn't a purge marker.
            marked(PLAIN, "menu_hasAiDiscoveryMenu", "panel_settingsMenu"),
            marked(EXTENSION, "android_purge_26_q3_ClipsMenu_hasAiDiscoveryMenu"),
        )
    }
}

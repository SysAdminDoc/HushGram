/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.postslist

import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePostsListHookTest {
    // Its switch starts off, so simple mode picks it (DefaultSelectionPolicyTest).
    @Test fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(profilePostsListPatch.default)
    }

    @Test fun theTabGoesToTheExtensionFirstInOnResumeAndNothingElseChanges() {
        val input = PostsTabFixture.classes()
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val original = PostsTabFixture.onResume().code()
        context.tellOnResume()
        val methods = context.mutableClassDefBy(PROFILE_MEDIA_TAB).methods
        assertResumeHook(methods.single { it.name == "onResume" }, original)
        assertEquals("the tab's other methods stay as they were",
            before.getValue(PROFILE_MEDIA_TAB).filter { !it.first.contains("->onResume(") },
            snapshot(methods.filter { it.name != "onResume" }))
        for (candidate in input.filter { it.type != PROFILE_MEDIA_TAB }) {
            assertEquals("${candidate.type} changed", before[candidate.type], snapshot(context.mutableClassDefBy(candidate.type).methods))
        }
    }

    @Test fun aMissingTabIsRefused() = refuses("one posts tab onResume", PostsTabFixture.classes().filter { it.type != PROFILE_MEDIA_TAB })
    @Test fun aTabWithoutOnResumeIsRefused() = refuses("one posts tab onResume", PostsTabFixture.with(PostsTabFixture.tab(resume = false)))
    @Test fun aJumpToTheFirstInstructionIsRefused() = refuses("enters the posts tab's onResume at its first instruction",
        PostsTabFixture.with(PostsTabFixture.tab(loop = true)))
    @Test fun aTabThatNoLongerReadsItsTabNameIsRefused() = refuses("doesn't read $TAB_KEY",
        PostsTabFixture.with(PostsTabFixture.tab(keys = listOf(SELF_KEY))))
    @Test fun aTabThatNoLongerReadsWhoseProfileItIsIsRefused() = refuses("doesn't read $SELF_KEY",
        PostsTabFixture.with(PostsTabFixture.tab(keys = listOf(TAB_KEY))))
    @Test fun aTabWithoutItsGridFieldIsRefused() = refuses("no public $GRID_FIELD field",
        PostsTabFixture.with(PostsTabFixture.tab(gridField = "A00")))
    @Test fun aPrivateGridFieldIsRefused() = refuses("no public $GRID_FIELD field",
        PostsTabFixture.with(PostsTabFixture.tab(gridFlags = AccessFlags.PRIVATE.value)))
    @Test fun aTabThatIsNoFragmentIsRefused() = refuses("isn't a $FRAGMENT",
        PostsTabFixture.classes().filter { it.type != PostsTabFixture.BASE })
    @Test fun aFragmentWithoutIsResumedIsRefused() = refuses("has no public isResumed()Z",
        PostsTabFixture.with(PostsTabFixture.fragment(listOf("getArguments" to BUNDLE))))
    @Test fun aFragmentWithoutGetArgumentsIsRefused() = refuses("has no public getArguments()$BUNDLE",
        PostsTabFixture.with(PostsTabFixture.fragment(listOf("isResumed" to "Z"))))
    @Test fun aRenamedPostsTabIsRefused() = refuses("doesn't name the $POSTS_TAB tab",
        PostsTabFixture.with(PostsTabFixture.controller("profile_posts_grid")))
    @Test fun aMissingGridCellIsRefused() = refuses("no grid cell class", PostsTabFixture.classes().filter { it.type != GRID_CELL })
    @Test fun aMissingExtensionIsRefused() = refuses("extension has no public static resumed(Ljava/lang/Object;)V",
        PostsTabFixture.classes().filter { it.type != POSTS_LIST })
    @Test fun anExtensionAnsweringSomethingIsRefused() = refuses("extension has no public static resumed(Ljava/lang/Object;)V",
        PostsTabFixture.with(PostsTabFixture.extension("Z")))

    /** Refused for [reason], with every class as it was. */
    private fun refuses(reason: String, input: List<ClassDef>) {
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val refusal = assertThrows(PatchException::class.java) { context.tellOnResume() }
        assertTrue(refusal.message, refusal.message!!.startsWith("$POSTS_LIST_PATCH: ") && reason in refusal.message!!)
        input.forEach { assertEquals("${it.type} was edited before refusal", before[it.type], snapshot(context.mutableClassDefBy(it.type).methods)) }
    }

    companion object {
        /** The tab goes to the extension first, in its own register, and then Instagram's [original] onResume runs as it was. */
        internal fun assertResumeHook(method: Method, original: List<Instruction>) {
            val code = method.code()
            val self = method.implementation!!.registerCount - 1
            assertEquals(1, code.count { it.reference() == RESUMED })
            assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
            assertEquals(RESUMED, code[0].reference())
            assertEquals("the hook reads only the tab, p0", listOf(self), code[0].namedRegisters())
            assertEquals("one instruction comes in front, nothing else", original.size + 1, code.size)
            assertEquals(original.map { Triple(it.opcode, it.reference(), it.namedRegisters()) },
                code.drop(1).map { Triple(it.opcode, it.reference(), it.namedRegisters()) })
        }

        internal fun snapshot(methods: Iterable<Method>) = methods.map { method ->
            method.toString() to method.code().map { Triple(it.opcode, it.reference(), it.namedRegisters()) }
        }

        internal fun Method.code() = implementation?.instructions?.toList().orEmpty()
        internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()
    }
}

/** Stand-ins for 450's posts tab, the Fragment it extends, the tab controller, the grid cell and the extension. */
internal object PostsTabFixture {
    private const val OBJECT = "Ljava/lang/Object;"
    /** The tab's own superclass, between it and Fragment, as in 450. */
    const val BASE = "LX/02Ne;"

    fun classes(): List<ClassDef> = listOf(tab(), clazz(BASE, FRAGMENT, emptyList()), fragment(listOf("getArguments" to BUNDLE, "isResumed" to "Z")),
        controller(POSTS_TAB), clazz(GRID_CELL, "Landroid/widget/ImageView;", emptyList()), extension("V"))

    /** [classes] with [replacement] in place of the class of its type. */
    fun with(replacement: ClassDef): List<ClassDef> = classes().map { if (it.type == replacement.type) replacement else it }

    /** 450's onResume: the tab in v4, a trace marker, super's onResume, the trace closed. */
    fun onResume(loop: Boolean = false): Method {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction35c(Opcode.INVOKE_SUPER, 1, 4, 0, 0, 0, 0, ImmutableMethodReference(BASE, "onResume", emptyList(), "V")),
        )
        // A goto back to the start, four code units back.
        code += if (loop) ImmutableInstruction10t(Opcode.GOTO, -4) else ImmutableInstruction10x(Opcode.RETURN_VOID)
        return method(PROFILE_MEDIA_TAB, "onResume", emptyList(), "V", 5, code, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
    }

    fun tab(resume: Boolean = true, loop: Boolean = false, keys: List<String> = listOf(TAB_KEY, SELF_KEY),
            gridField: String = GRID_FIELD, gridFlags: Int = AccessFlags.PUBLIC.value): ClassDef {
        val onCreate = method(PROFILE_MEDIA_TAB, "onCreate", listOf(BUNDLE), "V", 3,
            keys.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) as Instruction } +
                ImmutableInstruction10x(Opcode.RETURN_VOID), AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
        // Another method of the same shape, which the hook leaves alone.
        val onPause = method(PROFILE_MEDIA_TAB, "onPause", emptyList(), "V", 1,
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
        val methods = listOfNotNull(if (resume) onResume(loop) else null, onCreate, onPause)
        val grid = ImmutableField(PROFILE_MEDIA_TAB, gridField, RECYCLER_VIEW, gridFlags, null, null, null)
        return clazz(PROFILE_MEDIA_TAB, BASE, methods, listOf(grid))
    }

    fun fragment(methods: List<Pair<String, String>>): ClassDef = clazz(FRAGMENT, OBJECT, methods.map { (name, returns) ->
        method(FRAGMENT, name, emptyList(), returns, 2, listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            if (returns == "Z") ImmutableInstruction11x(Opcode.RETURN, 0) else ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)
    })

    fun controller(postsTab: String): ClassDef = clazz(PROFILE_TAB_CONTROLLER, OBJECT, listOf(method(PROFILE_TAB_CONTROLLER, "A04",
        listOf(PROFILE_TAB_CONTROLLER), "V", 1, listOf(ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(postsTab)),
            ImmutableInstruction10x(Opcode.RETURN_VOID)), AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))

    fun extension(returns: String): ClassDef = clazz(POSTS_LIST, OBJECT, listOf(method(POSTS_LIST, "resumed", listOf(OBJECT), returns, 1,
        listOf(if (returns == "V") ImmutableInstruction10x(Opcode.RETURN_VOID) else ImmutableInstruction11x(Opcode.RETURN, 0)),
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))

    fun clazz(type: String, superclass: String, methods: List<Method>, fields: List<Field> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, fields, methods)

    fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, flags: Int): Method =
        ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, code, null, null))
}

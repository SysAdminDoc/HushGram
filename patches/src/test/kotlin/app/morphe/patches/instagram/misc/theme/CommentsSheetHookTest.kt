/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.theme

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.patches.instagram.misc.extension.originalName
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommentsSheetHookTest {
    @Test
    fun allHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(PAINT_COMMENTS_SHEET.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(PAINT_COMMENTS_SHEET, REPAINT_COMMENTS_SHEET, COMMENTS_CONTENT)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /** A host without the set-up or drag method is some other build, and the patch says so. */
    @Test
    fun aHostMissingAMethodFailsThePatch() {
        val ctx = "Landroid/content/Context;"
        val fragment = "Landroidx/fragment/app/Fragment;"
        fun method(name: String, vararg parameters: String, flags: Int = AccessFlags.PUBLIC.value) = ImmutableMethod(
            BOTTOM_SHEET_HOST, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
            ImmutableMethodImplementation(4, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        fun host(vararg methods: ImmutableMethod) =
            ImmutableClassDef(BOTTOM_SHEET_HOST, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods.toList())

        val whole = PatchContexts.of(listOf(host(method("A0W", ctx, fragment, "I"), method("F5J", "I", "I"))))
        val found = whole.findBottomSheetHooks()
        assertEquals("A0W", found.setUp.name)
        assertEquals("F5J", found.dragged.name)
        whole.blackenCommentsSheet(found)
        for (name in listOf("A0W", "F5J")) {
            val calls = whole.mutableClassDefBy(BOTTOM_SHEET_HOST).methods.single { it.name == name }
                .implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
            assertEquals(name, 1, calls.count { it.startsWith("Lapp/hushgram/extension/instagram/misc/CommentsSheet;") })
        }

        val noDrag = PatchContexts.of(listOf(host(method("A0W", ctx, fragment, "I"))))
        assertThrows(PatchException::class.java) { noDrag.findBottomSheetHooks() }
        val private = PatchContexts.of(listOf(host(method("A0W", ctx, fragment, "I"), method("A09", "I", "I", flags = AccessFlags.PRIVATE.value))))
        assertThrows(PatchException::class.java) { private.findBottomSheetHooks() }
        assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findBottomSheetHooks() }
    }

    /**
     * On every build of each declared version, the host has exactly one set-up method and one drag
     * method, and each return in them gets the hook.
     */
    @Test
    fun eachBuildHasTheHostsTwoMethodsToHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds: List<File> =
            Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } } + Fixtures.otherBuilds()
        for (build in builds) {
            val host = FixtureDex.classes(build, setOf(BOTTOM_SHEET_HOST))[BOTTOM_SHEET_HOST]
                ?: throw AssertionError("${build.parentFile.name}/${build.name}: no $BOTTOM_SHEET_HOST")
            val context = PatchContexts.of(listOf(host))
            val hooks = context.findBottomSheetHooks()
            val returns = listOf(hooks.setUp, hooks.dragged).map { method ->
                method.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
            }
            assertTrue("${build.name}: a method without a return", returns.all { it > 0 })

            context.blackenCommentsSheet(hooks)

            val patched = context.mutableClassDefBy(BOTTOM_SHEET_HOST)
            val calls = listOf(hooks.setUp, hooks.dragged).map { hook ->
                patched.methods.single { it.name == hook.name && it.parameterTypes.map(Any::toString) == hook.parameterTypes.map(Any::toString) }
                    .implementation!!.instructions.count {
                        (it as? ReferenceInstruction)?.reference?.toString()
                            ?.startsWith("Lapp/hushgram/extension/instagram/misc/CommentsSheet;") == true
                    }
            }
            assertEquals("${build.parentFile.name}/${build.name}: one hook per return", returns, calls)
        }
    }

    /**
     * The hook the 450 comments sheet actually reaches: on every build, the comments screens (found
     * by their kept source names) include CommentListBottomsheetFragment, each declares one
     * onViewCreated, and each gets exactly one call at its very start.
     */
    @Test
    fun eachBuildHasTheCommentsScreensToHook() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val builds: List<File> =
            Fixtures.files { file -> file.extension == "apks" && versions.any { file.name.contains("-$it-") } } + Fixtures.otherBuilds()
        for (build in builds) {
            val screens = mutableListOf<ClassDef>()
            FixtureDex.forEach(build) { dex ->
                for (classDef in dex.classes) {
                    val copy = ImmutableClassDef.of(classDef)
                    if (copy.originalName()?.startsWith(COMMENTS_SCREEN_PREFIX) == true) screens += copy
                }
            }
            val label = "${build.parentFile.name}/${build.name}"
            assertTrue("$label: no CommentListBottomsheetFragment", screens.any { it.originalName() == "CommentListBottomsheetFragment" })
            val context = PatchContexts.of(screens)
            val found = context.findCommentsScreens()
            assertTrue("$label: the fragment has no onViewCreated", screens.single { it.originalName() == "CommentListBottomsheetFragment" }.type in found)
            context.blackenCommentsScreens(found)
            for (type in found) {
                val hooked = context.mutableClassDefBy(type).methods.single { it.name == "onViewCreated" }.implementation!!.instructions.toList()
                val calls = hooked.withIndex().filter { (it.value as? ReferenceInstruction)?.reference?.toString() == COMMENTS_CONTENT }
                assertEquals("$label/$type: one call", 1, calls.size)
                assertEquals("$label/$type: first instruction", 0, calls.single().index)
            }
        }
    }

    @Test
    fun noCommentsScreenFailsThePatch() {
        assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findCommentsScreens() }
    }
}

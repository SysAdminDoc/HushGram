/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.cache

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clear the media cache's one hook: the method that names the video cache's folders tells the
 * extension first, with the folder it's handed, so a clear asked for can move the video cache aside
 * before the player builds it. Anything the patch can't pick out fails it before a change.
 */
class MediaCacheHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(MEDIA_CACHE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BEFORE_VIDEO_CACHE is not in the extension: $declared", BEFORE_VIDEO_CACHE.substringAfter("->") in declared)
    }

    @Test
    fun theFolderMethodTellsTheExtensionFirst() {
        val context = PatchContexts.of(listOf(folders()))
        val before = folders().methods.single().instructions()

        context.clearVideosAtStart()

        val method = context.mutableClassDefBy(FOLDERS).methods.single()
        assertHookedFirst("stand-in", method)
        assertEquals("the method's own code follows", before.map { it.opcode }, method.instructions().drop(1).map { it.opcode })
    }

    @Test
    fun aMissingMethodFailsThePatch() =
        refuses("found none") { PatchContexts.of(listOf(folders(names = VIDEO_CACHE_FOLDERS.take(2)))).clearVideosAtStart() }

    @Test
    fun twoMethodsFailThePatch() =
        refuses("found ") { PatchContexts.of(listOf(folders(), folders("Lfixture/OtherFolders;"))).clearVideosAtStart() }

    /** The names in an instance method, or one taking other arguments, aren't the folder method. */
    @Test
    fun aMethodOfAnotherShapeFailsThePatch() {
        refuses("found none") { PatchContexts.of(listOf(folders(static = false))).clearVideosAtStart() }
        refuses("found none") {
            PatchContexts.of(listOf(folders(parameters = listOf("I", "Ljava/lang/String;")))).clearVideosAtStart()
        }
    }

    /** In each declared build: the one folder method, telling the extension first. */
    @Test
    fun eachDeclaredBuildTellsTheExtensionFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = VIDEO_CACHE_FOLDERS.flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
                val context = PatchContexts.of(holders.map { ImmutableClassDef.of(it) })

                context.clearVideosAtStart()

                val hooked = holders.flatMap { holder ->
                    context.mutableClassDefBy(holder.type).methods.filter { method ->
                        method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == BEFORE_VIDEO_CACHE }
                    }
                }
                assertEquals("${bundle.name}: hooked methods", 1, hooked.size)
                assertHookedFirst("${bundle.name} ${hooked.single().definingClass}->${hooked.single().name}", hooked.single())
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    private fun refuses(reason: String, patch: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { patch() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    /** The call first, once, handing over the folder parameter. */
    private fun assertHookedFirst(what: String, method: Method) {
        val code = method.instructions()
        assertTrue("$what: static", AccessFlags.STATIC.isSet(method.accessFlags))
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the hook", BEFORE_VIDEO_CACHE, (code[0] as ReferenceInstruction).reference.toString())
        val folder = method.implementation!!.registerCount - 1
        assertEquals("$what: the folder handed over", folder, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: hooks", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == BEFORE_VIDEO_CACHE })
    }

    private companion object {
        const val FOLDERS = "Lfixture/VideoCacheFolders;"

        /** Shaped like 450's: static (Integer, String) answering a File, naming the three folders. */
        fun folders(
            type: String = FOLDERS,
            names: List<String> = VIDEO_CACHE_FOLDERS,
            static: Boolean = true,
            parameters: List<String> = listOf("Ljava/lang/Integer;", "Ljava/lang/String;"),
        ): ClassDef {
            val code = names.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
            val access = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "A00", parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/io/File;", access, null, null,
                        ImmutableMethodImplementation(if (static) 3 else 4, code, null, null),
                    ),
                ),
            )
        }
    }
}

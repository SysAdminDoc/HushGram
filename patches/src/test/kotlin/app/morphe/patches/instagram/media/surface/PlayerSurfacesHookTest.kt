/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.surface

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Groot's surfaceCreated hands PlayerSurfaces the Surface its player draws into and the listener's
 * SurfaceView right after it stores that Surface, on every build, and its own code carries on unchanged
 * (#462, #583, #801).
 */
class PlayerSurfacesHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PLAYER_SURFACES).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$PLAYER_SURFACES->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("PlayerSurfaces declares no $DRAWS_INTO: $declared", DRAWS_INTO in declared)
    }

    @Test
    fun theSurfaceGoesOverRightAfterTheStore() {
        val context = PatchContexts.of(listOf(listener()))
        val before = context.findPlayerSurfaceStore()!!.method.code()
        val hooked = context.tellPlayerSurfaces()
        assertNotNull(hooked)
        assertHooked("fixture", before, hooked!!.code(), STORE)
    }

    @Test
    fun twoSurfaceViewsLeaveTheListenerAlone() {
        val context = PatchContexts.of(listOf(listener(secondView = true)))
        assertNull(context.findPlayerSurfaceStore())
        assertNull(context.tellPlayerSurfaces())
    }

    /** A Surface kept in the listener's own field, as it clears its spare, isn't the one its player draws into. */
    @Test
    fun aSurfaceInTheListenersOwnFieldIsNotThePlayers() {
        assertNull(PatchContexts.of(listOf(listener(ownField = true))).findPlayerSurfaceStore())
    }

    /** Something jumping to the instruction after the store would skip the hook, so it stays out. */
    @Test
    fun aJumpToTheNextInstructionKeepsTheHookOut() {
        assertNull(PatchContexts.of(listOf(listener(jumpAfterStore = true))).tellPlayerSurfaces())
    }

    @Test
    fun eachDeclaredBuildHandsTheSurfaceOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHandsTheSurfaceOverToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, GROOT_SURFACE_CREATED)))
        val found = context.findPlayerSurfaceStore()
        assertNotNull("$label: no one Groot surfaceCreated storing its player's Surface", found)
        val before = found!!.method.code()
        val hooked = context.tellPlayerSurfaces()
        assertNotNull("$label: the store can't take the hook", hooked)
        assertHooked(label, before, hooked!!.code(), found.store)
    }

    private fun assertHooked(label: String, before: List<Instruction>, code: List<Instruction>, store: Int) {
        val put = code[store] as TwoRegisterInstruction
        assertEquals("$label: the store stays", Opcode.IPUT_OBJECT, code[store].opcode)
        val read = code[store + 1]
        assertEquals("$label: reads the SurfaceView next", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$label: from the listener", put.registerB, (read as TwoRegisterInstruction).registerB)
        assertEquals("$label: its SurfaceView field", "Landroid/view/SurfaceView;", ((read as ReferenceInstruction).reference as FieldReference).type)
        val call = code[store + 2] as Instruction35c
        assertEquals("$label: then hands both over", DRAWS_INTO, call.reference.toString())
        assertEquals("$label: the SurfaceView first", read.registerA, call.registerC)
        assertEquals("$label: then the stored Surface", put.registerA, call.registerD)
        assertEquals("$label: Instagram's own code is kept", before.map { it.opcode }, (code.take(store + 1) + code.drop(store + 3)).map { it.opcode })
    }

    /** A stand-in Groot listener whose surfaceCreated stores the holder's Surface into its superclass's field. */
    private fun listener(secondView: Boolean = false, ownField: Boolean = false, jumpAfterStore: Boolean = false): ClassDef {
        val target = if (ownField) LISTENER else CALLBACK
        val method = MutableMethod(
            ImmutableMethod(
                LISTENER, "surfaceCreated", listOf(ImmutableMethodParameter("Landroid/view/SurfaceHolder;", null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(
            0,
            """
                const-string v0, "$GROOT_SURFACE_CREATED"
                invoke-interface { p1 }, Landroid/view/SurfaceHolder;->getSurface()Landroid/view/Surface;
                move-result-object v1
                ${if (jumpAfterStore) "if-eqz v1, :after" else "nop"}
                iput-object v1, p0, $target->surface:Landroid/view/Surface;
                :after
                iget-object v0, p0, $LISTENER->player:Ljava/lang/Object;
                return-void
            """.trimIndent(),
        )
        val fields = listOfNotNull(
            ImmutableField(LISTENER, "view", "Landroid/view/SurfaceView;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
            if (secondView) ImmutableField(LISTENER, "other", "Landroid/view/SurfaceView;", AccessFlags.PUBLIC.value, null, null, null) else null,
            ImmutableField(LISTENER, "player", "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null, null),
            ImmutableField(LISTENER, "surface", "Landroid/view/Surface;", AccessFlags.PUBLIC.value, null, null, null),
        )
        return ImmutableClassDef(LISTENER, AccessFlags.PUBLIC.value, CALLBACK, null, null, null, fields, listOf(ImmutableMethod.of(method)))
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private companion object {
        const val LISTENER = "Lfixture/Groot;"
        const val CALLBACK = "Lfixture/Callback;"
        /** Where the stand-in's store is: const-string, invoke, move-result, the nop or branch, then the store. */
        const val STORE = 4
    }
}

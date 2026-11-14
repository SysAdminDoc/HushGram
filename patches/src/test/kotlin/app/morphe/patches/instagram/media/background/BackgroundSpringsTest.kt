/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.background

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BackgroundSpringsTest {
    private val system = "Lfixture/SpringSystem;"
    private val looper = "Lfixture/SpringLooper;"
    private val frameCallback = "Landroid/view/Choreographer\$FrameCallback;"
    private val choreographer = "Landroid/view/Choreographer;"
    private val string = "Ljava/lang/String;"

    /**
     * The activate hands the extension its looper's frame callback first thing, read from this
     * (p0, v3 here), and a system with no looper lands on Instagram's own first instruction, whose
     * code stays as it was.
     */
    @Test
    fun theActivateTellsTheExtensionFirst() {
        val context = PatchContexts.of(classes())
        val springs = context.findSpringActivate("test")
        assertEquals("A03", springs.activate.name)
        assertEquals("$system->A01:$looper", springs.looper.toString())
        assertEquals("$looper->A03:$frameCallback", springs.frames.toString())

        val stock = context.method(system, "A03").code()
        context.keepSpringsMoving("test", springs)
        val code = context.method(system, "A03").code()

        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
        assertEquals("this into v0", listOf(0, 3), (code[0] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals("$system->A01:$looper", code[1].referenceText())
        assertEquals(listOf(0, 0), (code[1] as TwoRegisterInstruction).let { listOf(it.registerA, it.registerB) })
        assertEquals(Opcode.IF_EQZ, code[2].opcode)
        assertEquals("no looper runs Instagram's own code", 5, code.target(2))
        assertEquals("$looper->A03:$frameCallback", code[3].referenceText())
        assertEquals(SPRING_MOVING, code[4].referenceText())
        assertEquals("hands the callback", listOf(0), code[4].rangeRegisters())
        assertEquals("Instagram's own code follows as it was", stock.map { it.opcode }, code.drop(5).map { it.opcode })
        assertEquals(1, code.count { it.referenceText() == SPRING_MOVING })
        assertTrue(
            "the static helper loading the same message is left alone",
            context.method(system, "A04").code().none { it.referenceText() == SPRING_MOVING },
        )
    }

    /** The activate is refused when it reads no frame callback, or when two methods could be it. */
    @Test
    fun anUnclearActivateIsRefused() {
        for ((what, classes) in listOf(
            "no frame callback" to classes(readsFrames = false),
            "two activates" to classes(secondActivate = true),
        )) {
            try {
                PatchContexts.of(classes).findSpringActivate("test")
                fail("$what was taken")
            } catch (expected: PatchException) {
            }
        }
    }

    /** On each declared build the activate and both fields are found and the call goes in. */
    @Test
    fun eachDeclaredBuildTakesTheHook() {
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

    /** The same on every other build of the version. */
    @Test
    fun eachOtherBuildTakesTheHookToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = FixtureDex.classesLoading(bundle, SPRING_UNREGISTERED)
        val context = PatchContexts.of((holders + ExtensionDex.classDef(BACKGROUND_PLAY)).distinctBy { it.type })
        val springs = context.findSpringActivate("test")
        if ("386014758" in label) {
            assertEquals(
                "$label: Instagram's names",
                listOf("LX/02iJ;->A03", "LX/02iJ;->A01:LX/02iI;", "LX/02iI;->A03:$frameCallback"),
                listOf("${springs.activate.definingClass}->${springs.activate.name}", springs.looper.toString(), springs.frames.toString()),
            )
        }
        context.keepSpringsMoving("test", springs)
        val code = context.method(springs.activate.definingClass, springs.activate.name, listOf(string)).code()
        assertEquals("$label: the call goes in first", SPRING_MOVING, code[4].referenceText())
        assertEquals("$label: and only there", 1, code.count { it.referenceText() == SPRING_MOVING })
        assertEquals("$label: no looper runs Instagram's own code", 5, code.target(2))
    }

    /**
     * Rebound's spring system as 454 has it: the activate looks the spring up, marks the system busy
     * and, with its looper idle, posts the looper's frame callback. [readsFrames] false leaves the
     * post out; [secondActivate] adds an instance twin loading the same message.
     */
    private fun classes(readsFrames: Boolean = true, secondActivate: Boolean = false): List<ClassDef> {
        val post = if (readsFrames) {
            """
                iget-object v1, v2, $looper->A04:$choreographer
                iget-object v0, v2, $looper->A03:$frameCallback
                invoke-virtual { v1, v0 }, $choreographer->postFrameCallback($frameCallback)V
            """
        } else {
            ""
        }
        val activate = method("A03", body = """
            iget-object v0, p0, $system->A02:Ljava/util/Map;
            invoke-interface { v0, p1 }, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v1
            if-eqz v1, :missing
            iget-boolean v0, p0, $system->A00:Z
            if-eqz v0, :done
            const/4 v0, 0x0
            iput-boolean v0, p0, $system->A00:Z
            iget-object v2, p0, $system->A01:$looper
            iget-boolean v0, v2, $looper->A02:Z
            if-nez v0, :done
            $post
            :done
            return-void
            :missing
            const-string v0, "$SPRING_UNREGISTERED"
            new-instance v1, Ljava/lang/IllegalArgumentException;
            invoke-direct { v1, v0 }, Ljava/lang/IllegalArgumentException;-><init>($string)V
            throw v1
        """)
        // A static helper building the same message, which is no activate.
        val helper = method("A04", static = true, body = """
            const-string v0, "$SPRING_UNREGISTERED"
            return-void
        """)
        val methods = mutableListOf(activate, helper)
        if (secondActivate) {
            methods += method("A05", body = """
                const-string v0, "$SPRING_UNREGISTERED"
                iget-object v2, p0, $system->A01:$looper
                iget-object v0, v2, $looper->A03:$frameCallback
                return-void
            """)
        }
        return listOf(
            ImmutableClassDef(system, PUBLIC, "Ljava/lang/Object;", null, null, null, null, methods),
            ExtensionDex.classDef(BACKGROUND_PLAY),
        )
    }

    private fun method(name: String, static: Boolean = false, body: String): Method {
        val access = PUBLIC or (if (static) AccessFlags.STATIC.value else 0)
        // Three locals, then this and the id: p0 is v3, as on 454.
        val registers = if (static) 4 else 5
        val mutable = MutableMethod(
            ImmutableMethod(
                system, name, listOf(ImmutableMethodParameter(string, null, null)), "V", access, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>? = null): Method =
        classDefBy(type).methods.first { it.name == name && (parameters == null || it.parameterTypes.map(Any::toString) == parameters) }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.rangeRegisters(): List<Int> {
        val call = this as RegisterRangeInstruction
        return (call.startRegister until call.startRegister + call.registerCount).toList()
    }

    /** The index the branch at [index] lands on. */
    private fun List<Instruction>.target(index: Int): Int {
        val address = IntArray(size + 1)
        forEachIndexed { i, instruction -> address[i + 1] = address[i] + instruction.codeUnits }
        return address.indexOf(address[index] + (this[index] as OffsetInstruction).codeOffset)
    }

    private companion object {
        val PUBLIC = AccessFlags.PUBLIC.value
    }
}

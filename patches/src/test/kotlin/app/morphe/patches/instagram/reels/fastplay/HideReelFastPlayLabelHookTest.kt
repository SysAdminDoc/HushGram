/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fastplay

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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HideReelFastPlayLabelHookTest {
    private val part = "Lfixture/FastPlayPart;"
    private val composer = "Lfixture/Composer;"
    private val label = "Lfixture/Label;"
    private val component = "Lfixture/Component;"
    private val state = "Lfixture/State;"
    private val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

    /** The hook the patch writes is in the FastPlayLabel the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val owner = FAST_PLAY_LABEL.substringBefore("->")
        val declared = ExtensionDex.classDef(owner).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FAST_PLAY_LABEL is not in the extension: $declared", FAST_PLAY_LABEL.substringAfter("->") in declared)
    }

    /**
     * A fast play part shaped like 454's 0Bcw: the draw returns a plain component on one branch, the label
     * builder's answer on another and null on the last. Only the label's return passes through the hook,
     * cast back to the builder's type, and nothing stock is taken out.
     */
    @Test
    fun onlyTheLabelReturnIsFiltered() {
        val context = PatchContexts.of(classes())
        val stock = context.render().code()

        val found = context.findFastPlayLabel()
        assertEquals("the draw's class", part, found.render.definingClass)
        assertEquals("the label builder's type", label, found.type)
        context.hideFastPlayLabel(found)

        val patched = context.render().code()
        assertFiltered("fixture", patched, label)
        assertTrue("stock instructions kept in order", isKept(stock, patched))
        assertEquals("stock returns", 3, stock.count { it.opcode == Opcode.RETURN_OBJECT })
        assertEquals("the null and plain returns don't pass through the hook", 2,
            patched.indices.count { patched[it].opcode == Opcode.RETURN_OBJECT && patched.getOrNull(it - 3)?.referenceText() != FAST_PLAY_LABEL })
        assertEquals("the label builder is untouched", 0,
            context.mutableClassDefBy(part).methods.single { it.name == "label" }.code().count { it.referenceText() == FAST_PLAY_LABEL })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(drawLoads = false) to "expected one fast play draw loading \"$FAST_PLAY_RENDER\", found 0",
            classes(labelLoads = false) to "to call its label builder loading \"$FAST_PLAY_LABEL_RENDER\" once, found 0",
            classes(callsLabelTwice = true) to "to call its label builder loading \"$FAST_PLAY_LABEL_RENDER\" once, found 2",
            classes(keepsLabel = true) to "doesn't return its label builder's answer straight away",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hideFastPlayLabel(context.findFastPlayLabel()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == FAST_PLAY_LABEL } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the draw and its label return are found, and the label alone is filtered. */
    @Test
    fun eachDeclaredBuildFiltersTheLabel() {
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
    fun eachOtherBuildFiltersTheLabelToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, FAST_PLAY_RENDER) + FixtureDex.classesLoading(bundle, FAST_PLAY_LABEL_RENDER)
        val classes = FixtureDex.withStringPools(bundle, loading.distinctBy { it.type })
        val context = PatchContexts.of(classes)

        val found = context.findFastPlayLabel()
        val stock = found.render.code()
        assertEquals("$label: one label call", 1, stock.count { it.referenceText()?.let { text -> text.endsWith(")${found.type}") && text.startsWith(found.render.definingClass) } == true })
        context.hideFastPlayLabel(found)

        val patched = context.mutableClassDefBy(found.render.definingClass).methods.single {
            it.name == found.render.name && it.parameterTypes.map(Any::toString) == found.render.parameterTypes.map(Any::toString)
        }.code()
        assertFiltered(label, patched, found.type)
        assertTrue("$label: stock instructions kept in order", isKept(stock, patched))
        assertEquals(
            "$label: methods hooked",
            1,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == FAST_PLAY_LABEL } },
        )
    }

    /**
     * One hook call: a range call over the label call's result register, its answer back in that register,
     * cast to [type], and returned. The label call's move-result goes straight on to it, or to a goto that
     * leads to it when a try range covers the return.
     */
    private fun assertFiltered(what: String, code: List<Instruction>, type: String) {
        val calls = code.indices.filter { code[it].referenceText() == FAST_PLAY_LABEL }
        assertEquals("$what: hook calls", 1, calls.size)
        val at = calls.single()
        val range = code[at] as RegisterRangeInstruction
        val register = range.startRegister
        assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
        assertEquals("$what: registers handed to the hook", 1, range.registerCount)
        assertEquals("$what: the hook's answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[at + 2].opcode)
        assertEquals("$what: the cast's register", register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast's type", type, ((code[at + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: the return", Opcode.RETURN_OBJECT, code[at + 3].opcode)
        assertEquals("$what: the return's register", register, (code[at + 3] as OneRegisterInstruction).registerA)

        val labelCall = code.indices.single {
            val reference = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
            (code[it].opcode == Opcode.INVOKE_DIRECT || code[it].opcode == Opcode.INVOKE_DIRECT_RANGE) && reference?.returnType == type
        }
        assertEquals("$what: the label's result", Opcode.MOVE_RESULT_OBJECT, code[labelCall + 1].opcode)
        assertEquals("$what: the label's register", register, (code[labelCall + 1] as OneRegisterInstruction).registerA)
        val next = code[labelCall + 2]
        assertTrue("$what: after the label call comes ${next.opcode}", labelCall + 2 == at || next.opcode in gotos)
    }

    /** Whether every stock instruction is still there in order, a goto of any width read as one and the replaced return as a goto. */
    private fun isKept(stock: List<Instruction>, patched: List<Instruction>): Boolean {
        fun Instruction.kind() = if (opcode in gotos) Opcode.GOTO else opcode
        val wanted = stock.filter { it.opcode != Opcode.NOP }
        val have = patched.filter { it.opcode != Opcode.NOP }
        var at = 0
        for (instruction in have) {
            if (at < wanted.size && instruction.kind() == wanted[at].kind() &&
                instruction.referenceText() == wanted[at].referenceText()) {
                at++
            } else if (at < wanted.size && wanted[at].opcode == Opcode.RETURN_OBJECT && instruction.kind() == Opcode.GOTO) {
                at++
            }
        }
        return at == wanted.size
    }

    /**
     * A fast play part shaped like 454's 0Bcw: the draw loads its marker, returns a plain component when a field
     * says so, the label builder's answer when the state's ordinal is over zero, and null otherwise. The label
     * builder loads its own marker.
     */
    private fun classes(
        drawLoads: Boolean = true,
        labelLoads: Boolean = true,
        callsLabelTwice: Boolean = false,
        keepsLabel: Boolean = false,
    ): List<ClassDef> {
        val instance = AccessFlags.PUBLIC.value
        val labelCall = "invoke-direct {p0, p1, v3}, $part->label($composer$state)$label"
        val drawBody = """
            const/4 v4, 0x0
            ${if (drawLoads) "const-string v0, \"$FAST_PLAY_RENDER\"" else "const-string v0, \"other\""}
            invoke-static {v0}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            iget-object v3, p0, $part->state:$state
            iget-boolean v0, p0, $part->plain:Z
            if-eqz v0, :fast
            new-instance v0, $component
            invoke-direct {v0}, $component-><init>()V
            return-object v0
            :fast
            invoke-virtual {v3}, Ljava/lang/Enum;->ordinal()I
            move-result v0
            if-le v0, v4, :none
            ${if (callsLabelTwice) "$labelCall\nmove-result-object v1" else ""}
            $labelCall
            move-result-object v0
            ${if (keepsLabel) "move-object v1, v0\nreturn-object v1" else "return-object v0"}
            :none
            const/4 v0, 0x0
            return-object v0
        """
        val labelBody = """
            ${if (labelLoads) "const-string v0, \"$FAST_PLAY_LABEL_RENDER\"" else "const-string v0, \"other\""}
            invoke-static {v0}, Lfixture/Trace;->begin(Ljava/lang/String;)V
            new-instance v0, $label
            invoke-direct {v0}, $label-><init>()V
            return-object v0
        """
        return listOf(
            classDef(part, listOf(
                method(part, "draw", listOf(composer), component, 5, instance, drawBody),
                method(part, "label", listOf(composer, state), label, 1, AccessFlags.PRIVATE.value, labelBody),
            )),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null, emptyList(), methods)

    private fun BytecodePatchContext.render(): Method = mutableClassDefBy(part).methods.single { it.name == "draw" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

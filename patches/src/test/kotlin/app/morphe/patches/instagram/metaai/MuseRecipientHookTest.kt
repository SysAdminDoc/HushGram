/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide Meta AI in the share sheet, its grid of people (#879): the Meta AI share action the grid's
 * adapter is handed goes through the hook before the setter reads it.
 */
class MuseRecipientHookTest {
    private val adapter = "Lfixture/ShareSheetGrid;"
    private val action = "Lfixture/MetaAiShareAction;"
    private val otherAction = "Lfixture/StoryShareAction;"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(MUSE_RECIPIENT.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MUSE_RECIPIENT is not in the extension: $declared", MUSE_RECIPIENT.substringAfter("->") in declared)
    }

    /** The setter taking Meta AI's action asks first; its sibling taking another action and the rest stay. */
    @Test
    fun theSetterAsksBeforeItTestsTheAction() {
        val context = PatchContexts.of(classes())
        val site = context.findMuseRecipientSetter()
        assertEquals(listOf(adapter, "setMuse", action, 2), listOf(site.type, site.name, site.muse, site.register))

        context.holdMuseRecipient(site)

        val methods = context.mutableClassDefBy(adapter).methods
        assertAsked("the stand-in", methods.single { it.name == "setMuse" }.code(), 2, action)
        assertTrue(
            "a method that isn't the setter was touched",
            methods.filter { it.name != "setMuse" }.none { method -> method.code().any { it.referenceText() == MUSE_RECIPIENT } },
        )
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(hatch = false) to "0 share sheet grid setters",
            classes(grid = false) to "0 share sheet grid setters",
            classes(adapters = 2) to "2 share sheet grid setters",
            classes(testsFirst = false) to "doesn't test Meta AI's share action first",
            classes(keeps = false) to "doesn't keep Meta AI's share action",
            classes(enters = true) to "a branch enters",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(PatchException::class.java) { context.findMuseRecipientSetter() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.flatMap { owner -> context.mutableClassDefBy(owner.type).methods }
                .filter { method -> method.code().any { it.referenceText() == MUSE_RECIPIENT } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build, and in every other build of a declared version, the setter is the
     * share sheet grid adapter's (the class whose binder casts to the grid item's holder, a name
     * Instagram's build keeps), it takes Meta AI's action (the class naming "hatch" and the grid's
     * Muse cell), and the hook goes in front of its first test with the rest of it as it was.
     * 386314286 asks a pool for the grid's name, and 386314340 has no check method of its own.
     */
    @Test
    fun eachBuildAsksInTheGridsSetter() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            asksInTheGridsSetter(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (base in Fixtures.otherBuilds()) asksInTheGridsSetter(base, base.parentFile.name)
    }

    private fun asksInTheGridsSetter(bundle: java.io.File, label: String) {
        val sliced = (FixtureDex.classesLoading(bundle, GRID_ADAPTER) + FixtureDex.classesLoading(bundle, MUSE_GRID)).distinctBy { it.type }
        val classes = FixtureDex.withStringPools(bundle, sliced)
        val context = PatchContexts.of(classes)
        val site = context.findMuseRecipientSetter()
        val grid = classes.single { it.type == site.type }
        assertTrue(
            "$label: ${site.type} isn't the share sheet's grid adapter",
            grid.methods.any { method -> method.code().any { it.string()?.contains(GRID_HOLDER) == true } },
        )
        val stock = grid.methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }.code()
        assertEquals("$label: the setter takes Meta AI's action second", site.muse, site.parameters[1])
        assertTrue("$label: the setter tests the action first", (stock[0] as OneRegisterInstruction).registerA == site.register)

        context.holdMuseRecipient(site)

        val code = context.mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }.code()
        assertAsked(label, code, site.register, site.muse)
        val kept = code.drop(3).filter { it.opcode != Opcode.NOP }.map { kind(it.opcode) }
        assertEquals("$label: the setter's own code is kept", stock.filter { it.opcode != Opcode.NOP }.map { kind(it.opcode) }, kept)
    }

    /** The ask on the action's register, the answer back in it, cast back to the action's class, once. */
    private fun assertAsked(what: String, code: List<Instruction>, register: Int, muse: String) {
        assertEquals("$what: the ask comes first", MUSE_RECIPIENT, code[0].referenceText())
        val ask = code[0] as RegisterRangeInstruction
        assertEquals("$what: the ask takes the action", listOf(register, 1), listOf(ask.startRegister, ask.registerCount))
        assertEquals("$what: the answer back", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: in the same register", register, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$what: cast back", Opcode.CHECK_CAST, code[2].opcode)
        assertEquals("$what: that register", register, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: to the action's class", muse, ((code[2] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$what: one ask", 1, code.count { it.referenceText() == MUSE_RECIPIENT })
    }

    // ---- stand-ins shaped like Instagram 457's ------------------------------------------------

    /**
     * The grid's adapter holds [GRID_ADAPTER] and has two static setters of one shape: one taking
     * Meta AI's action, which names "hatch" and the grid's Muse cell, and one taking another action
     * that names neither. Each tests its action first and keeps it in a field. The setter's
     * registers are v0 and the parameters v1 (adapter), v2 (action), v3 (callback) and v4.
     */
    private fun classes(
        hatch: Boolean = true,
        grid: Boolean = true,
        adapters: Int = 1,
        testsFirst: Boolean = true,
        keeps: Boolean = true,
        enters: Boolean = false,
    ): List<ClassDef> {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        fun setter(owner: String, name: String, taken: String): Method {
            val field = if (keeps || taken != action) "$owner->muse:$taken" else "$owner->other:Ljava/lang/Object;"
            val body = """
                ${if (testsFirst) "" else "const/4 v0, 0x0"}
                ${if (enters) ":start" else ""}
                if-nez v2, :set
                iget-object v0, v1, $owner->muse:$taken
                if-nez v0, :set
                return-void
                :set
                iput-object v2, v1, $field
                iput-object v3, v1, $owner->callback:Lkotlin/jvm/functions/Function1;
                ${if (enters) "if-eqz v4, :start" else ""}
                return-void
            """
            return method(owner, name, listOf(owner, taken, "Lkotlin/jvm/functions/Function1;", "Z"), "V", 5, body, flags)
        }
        val adapterClasses = (0 until adapters).map { copy ->
            val type = if (copy == 0) adapter else "Lfixture/OtherShareSheetGrid;"
            classDef(type, listOf(
                method(type, "views", emptyList(), "Ljava/lang/String;", 2, "const-string v0, \"$GRID_ADAPTER\"\nreturn-object v0"),
                setter(type, "setMuse", action),
                setter(type, "setOther", otherAction),
            ))
        }
        val actionClass = classDef(action, listOfNotNull(
            if (hatch) method(action, "name", emptyList(), "Ljava/lang/String;", 2, "const-string v0, \"$HATCH_TARGET\"\nreturn-object v0") else null,
            if (grid) method(action, "surface", emptyList(), "Ljava/lang/String;", 2, "const-string v0, \"$MUSE_GRID\"\nreturn-object v0") else null,
        ))
        val otherClass = classDef(otherAction, listOf(
            method(otherAction, "name", emptyList(), "Ljava/lang/String;", 2, "const-string v0, \"add_to_story\"\nreturn-object v0"),
        ))
        return adapterClasses + actionClass + otherClass
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private companion object {
        /** The grid item's holder, which the grid adapter's binder casts to by a name Instagram's build keeps. */
        const val GRID_HOLDER = "instagram.features.direct.fragment.sharesheet.gridview.DirectShareSheetGridItemViewBinder"
    }

    private fun kind(opcode: Opcode) = if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

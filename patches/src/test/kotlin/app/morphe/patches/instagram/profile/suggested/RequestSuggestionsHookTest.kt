/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.suggested

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
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide Suggested for you on follow lists, on the Follow requests screen (#441): the request builder's flag, found and answered. */
class RequestSuggestionsHookTest {
    private val builder = "Lfixture/RequestBuilder;"
    private val variables = "Lfixture/Variables;"
    private val continuation = "Lfixture/Continuation;"
    private val put = "$variables->put(Ljava/lang/Object;Ljava/lang/String;Z)V"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(EXCLUDE_REQUEST_SUGGESTIONS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("not in the extension: $declared", EXCLUDE_REQUEST_SUGGESTIONS.substringAfter("->") in declared)
    }

    /** The builder's first boolean parameter is handed to the extension before anything reads it. */
    @Test
    fun theFirstBooleanIsAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().single().methods.single().code().size

        val site = context.findRequestSuggestions()
        context.hideRequestSuggestions(site)

        val patched = context.mutableClassDefBy(builder).methods.single()
        assertAnswered("stand-in", patched, site.register)
        assertEquals("what else was added", before + 2, patched.code().size)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(withExclude = false) to "expected one request builder loading",
            classes(copiesFrom = "p3") to "not its first boolean parameter",
            classes(computed = true) to "other than a copy of a parameter",
            classes(jumpToStart = true) to "jumps to its first instruction",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findRequestSuggestions() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertNull("$expected: warned and went on", context.findRequestSuggestionsOrWarn())
            assertTrue(
                "$expected: something was written",
                context.mutableClassDefBy(builder).methods.single().code().none { it.referenceText() == EXCLUDE_REQUEST_SUGGESTIONS },
            )
        }
    }

    /**
     * In each build the one method loading both variable names is the request builder, its first boolean
     * parameter is the flag, and it's answered at the method's start. On 453's 385914557 that's
     * FollowRequestsGraphQLDataSource.BJ2.
     */
    @Test
    fun eachDeclaredBuildAsksForNoSuggestions() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = FixtureDex.classesLoading(bundle, EXCLUDE_SUGGESTED_USERS)
            val context = PatchContexts.of(holders)

            val site = context.findRequestSuggestions()
            context.hideRequestSuggestions(site)

            assertTrue("${bundle.name}: the builder ${site.type}", site.type.contains("FollowRequestsGraphQLDataSource"))
            assertEquals("${bundle.name}: its parameters", listOf("Z", "Z"), site.parameters.takeLast(2))
            val patched = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
            assertAnswered(bundle.name, patched, site.register)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == EXCLUDE_REQUEST_SUGGESTIONS } } },
            )
        }
    }

    /** The method starts with the range call on the parameter's register and its answer back in it, ahead of the original code. */
    private fun assertAnswered(what: String, method: Method, register: Int) {
        val code = method.code().filter { it.opcode != Opcode.NOP }
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == EXCLUDE_REQUEST_SUGGESTIONS })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the hook", EXCLUDE_REQUEST_SUGGESTIONS, code[0].referenceText())
        assertEquals("$what: what it's handed", register, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the register", register, (code[1] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 453's: an instance method taking a continuation and two booleans, which names the
     * deferred suggested users, copies its first boolean and hands it over with the exclude key.
     */
    private fun classes(
        withExclude: Boolean = true,
        copiesFrom: String = "p2",
        computed: Boolean = false,
        jumpToStart: Boolean = false,
    ): List<ClassDef> {
        val body = listOfNotNull(
            if (jumpToStart) ":top" else null,
            "const-string v0, \"$DEFER_SUGGESTED_USERS\"",
            if (withExclude) "const-string v1, \"$EXCLUDE_SUGGESTED_USERS\"" else "const-string v1, \"page_size\"",
            if (computed) "const/4 v2, 0x1" else "move v2, $copiesFrom",
            "invoke-static {v3, v1, v2}, $put",
            if (jumpToStart) "if-eqz v2, :top" else null,
            "return-object p1",
        ).joinToString("\n")
        val method = method(
            builder, "build", listOf(continuation, "Z", "Z"), "Ljava/lang/Object;", 5, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, body,
        )
        return listOf(classDef(builder, "Ljava/lang/Object;", listOf(method)))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.size + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
}

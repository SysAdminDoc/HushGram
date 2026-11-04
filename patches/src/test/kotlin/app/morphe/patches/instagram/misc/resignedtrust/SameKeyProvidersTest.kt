/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.resignedtrust

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SameKeyProvidersTest {
    private val policy = "Lfixture/Policy;"
    private val hook = "Lapp/hushgram/extension/instagram/misc/InstagramSignature;->" +
        "isSameKeyFamilyProviderCaller(Landroid/content/Context;)Z"
    private val accessRefusal = "Component access not allowed for "
    private val killSwitch = "Content Provider blocked by kill switch for "
    private val otherPolicyTypes = setOf(
        "Lcom/facebook/secure/content/delegate/ThirdPartyContentProviderDelegate;",
        "Lcom/instagram/contentprovider/FamilyAppsUserValuesProvider;",
        "Lcom/instagram/contentprovider/AsyncFamilyAppsUserValuesProvider\$Impl;",
    )

    private fun method(owner: String, name: String, result: String, registers: Int, body: String,
                       parameters: List<String> = emptyList()): MutableMethod = MutableMethod(ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    )).apply { addInstructionsWithLabels(0, body) }

    private fun classDef(type: String, parent: String, methods: List<Method> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, parent, emptyList(), null, null, emptyList(), methods)

    private fun getter(name: String, field: String) = method(SAME_KEY_PROVIDER, name, policy, 2, """
        iget-object v0, p0, $SAME_KEY_PROVIDER->$field:$policy
        return-object v0
    """)

    private fun guard(name: String, getter: String, result: Int = 0, registers: Int = 5,
                      checker: String = "allow", branch: String = "if-eqz"): MutableMethod {
        val decision = if (branch == "if-eqz") "if-eqz v$result, :denied\nreturn-void" else
            "if-nez v$result, :allowed"
        val allowed = if (branch == "if-eqz") "" else ":allowed\nreturn-void"
        return method(PROVIDER_BASE, name, "V", registers, """
            invoke-static {}, Lfixture/Initialization;->ready()V
            move-object/from16 v2, p0
            check-cast v2, $TRUSTED_CALLER_PROVIDER
            iget-object v0, v2, Lfixture/ProviderHolder;->provider:Landroid/content/ContentProvider;
            invoke-virtual { v0 }, Landroid/content/ContentProvider;->getContext()Landroid/content/Context;
            move-result-object v1
            invoke-virtual { v2 }, $TRUSTED_CALLER_PROVIDER->$getter()$policy
            move-result-object v0
            invoke-static { v1, v0 }, Lfixture/NativePolicy;->$checker(Landroid/content/Context;$policy)Z
            move-result v$result
            $decision
            :denied
            new-instance v0, Ljava/lang/SecurityException;
            const-string v1, "native refusal"
            invoke-direct { v0, v1 }, Ljava/lang/SecurityException;-><init>(Ljava/lang/String;)V
            throw v0
            $allowed
        """)
    }

    private fun classes(
        guards: List<Method> = listOf(guard("queryGuard", "readPolicy"), guard("callGuard", "writePolicy", branch = "if-nez")),
        getters: List<Method> = listOf(getter("readPolicy", "read"), getter("writePolicy", "write")),
        assignments: String = "iput-object v0, p0, $SAME_KEY_PROVIDER->write:$policy",
    ): List<ClassDef> {
        val constructor = method(SAME_KEY_PROVIDER, "<init>", "V", 3, """
            invoke-direct { p0, p1 }, $TRUSTED_CALLER_PROVIDER-><init>(Landroid/content/ContentProvider;)V
            sget-object v0, Lfixture/SameKeyPolicy;->singleton:Lfixture/SameKeyPolicy;
            iput-object v0, p0, $SAME_KEY_PROVIDER->read:$policy
            $assignments
            return-void
        """, listOf("Landroid/content/ContentProvider;"))
        return listOf(
            classDef(PROVIDER_BASE, "Lfixture/ProviderHolder;", guards),
            classDef(TRUSTED_CALLER_PROVIDER, PROVIDER_BASE),
            classDef(SAME_KEY_PROVIDER, TRUSTED_CALLER_PROVIDER, getters + constructor),
            classDef(LOGGED_IN_USERS, SAME_KEY_PROVIDER),
        )
    }

    private fun List<Instruction>.boundary(address: Int): Int {
        var at = 0
        for (index in indices) {
            if (at == address) return index
            at += this[index].codeUnits
        }
        if (at == address) return size
        throw AssertionError("no instruction boundary at $address")
    }

    private fun List<Instruction>.target(index: Int): Int =
        boundary(take(index).sumOf { it.codeUnits } + (this[index] as OffsetInstruction).codeOffset)

    private fun Instruction.key(): List<Any?> = listOf(
        opcode, codeUnits, (this as? OneRegisterInstruction)?.registerA,
        (this as? TwoRegisterInstruction)?.registerB, (this as? ThreeRegisterInstruction)?.registerC,
        (this as? FiveRegisterInstruction)?.let {
            listOf(it.registerCount, it.registerC, it.registerD, it.registerE, it.registerF, it.registerG)
        },
        (this as? RegisterRangeInstruction)?.let { listOf(it.startRegister, it.registerCount) },
        (this as? WideLiteralInstruction)?.wideLiteral,
        (this as? ReferenceInstruction)?.reference?.toString(),
        (this as? SwitchPayload)?.switchElements?.map { listOf(it.key, it.offset) },
        (this as? ArrayPayload)?.let { listOf(it.elementWidth, it.arrayElements.map(Number::toLong)) },
    )

    private data class NativeBody(val frame: List<Any?>, val instructions: List<List<Any?>>,
                                  val branches: Map<Int, Int>, val protectedRegions: List<List<Any?>>)

    /** Capture operands and complete native control flow before any mutable proxy can alter them. */
    private fun snapshot(method: Method): NativeBody {
        val implementation = method.implementation
        val code = implementation?.instructions?.toList().orEmpty()
        return NativeBody(
            listOf(method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString),
                method.returnType, method.accessFlags, implementation?.registerCount),
            code.map { it.key() },
            code.indices.filter { code[it] is OffsetInstruction }.associateWith { code.target(it) },
            implementation?.tryBlocks?.map { block -> listOf(
                code.boundary(block.startCodeAddress), code.boundary(block.startCodeAddress + block.codeUnitCount),
                block.exceptionHandlers.map { listOf(it.exceptionType, code.boundary(it.handlerCodeAddress)) },
            ) }.orEmpty(),
        )
    }

    private fun verifyInjection(target: ProviderDecision, method: MutableMethod, before: NativeBody) {
        val code = method.implementation!!.instructions.toList()
        val after = snapshot(method)
        val index = target.index
        fun shifted(original: Int) = original + if (original >= index) 6 else 0
        assertEquals("native register frame and method identity", before.frame, after.frame)
        assertEquals(before.instructions.size + 6, code.size)
        assertEquals("every original operand and instruction survives",
            before.instructions, after.instructions.take(index) + after.instructions.drop(index + 6))
        assertEquals("every original branch keeps the same native destination",
            before.branches.mapKeys { shifted(it.key) }.mapValues { shifted(it.value) },
            after.branches.filterKeys { it < index || it >= index + 6 })
        assertEquals("native exception paths survive", before.protectedRegions.map { region ->
            listOf(shifted(region[0] as Int), shifted(region[1] as Int),
                (region[2] as List<*>).map { entry -> (entry as List<*>).let {
                    listOf(it[0], shifted(it[1] as Int))
                } })
        }, after.protectedRegions)
        assertEquals(Opcode.IF_NEZ, code[index].opcode)
        assertEquals(target.result, (code[index] as OneRegisterInstruction).registerA)
        assertEquals("native yes skips the helper", index + 6, code.target(index))
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[index + 1].opcode)
        assertEquals(target.result, (code[index + 1] as TwoRegisterInstruction).registerA)
        assertEquals(method.implementation!!.registerCount - 1, (code[index + 1] as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.INSTANCE_OF, code[index + 2].opcode)
        assertEquals(target.result, (code[index + 2] as TwoRegisterInstruction).registerA)
        assertEquals(target.result, (code[index + 2] as TwoRegisterInstruction).registerB)
        assertEquals(SAME_KEY_PROVIDER, (code[index + 2] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.IF_EQZ, code[index + 3].opcode)
        assertEquals(target.result, (code[index + 3] as OneRegisterInstruction).registerA)
        assertEquals("another policy keeps its native false", index + 6, code.target(index + 3))
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[index + 4].opcode)
        assertEquals(target.context, (code[index + 4] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (code[index + 4] as RegisterRangeInstruction).registerCount)
        assertEquals(hook, (code[index + 4] as ReferenceInstruction).reference.toString())
        assertEquals(Opcode.MOVE_RESULT, code[index + 5].opcode)
        assertEquals(target.result, (code[index + 5] as OneRegisterInstruction).registerA)
        assertEquals(1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == hook })
    }

    private fun verifyInjection(target: ProviderDecision) {
        val before = snapshot(target.method)
        val method = MutableMethod(target.method)
        method.repairSameKeyProviderDecision(target)
        verifyInjection(target, method, before)
    }

    private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
    }?.toSet().orEmpty()

    private fun Method.calls(): List<MethodReference> = implementation?.instructions?.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)
    }?.toList().orEmpty()

    private data class NativeProviders(val classes: List<ClassDef>, val inlineAnchors: List<Method>)
    private val nativeCache = mutableMapOf<String, NativeProviders>()

    /** The anchor pair is checked across every native class, not just the kept provider roles. */
    private fun nativeProviders(bundle: File): NativeProviders = nativeCache.getOrPut(bundle.absolutePath) {
        val found = mutableListOf<ClassDef>()
        val inlineAnchors = mutableListOf<Method>()
        FixtureDex.forEach(bundle) { dex ->
            val searchAnchors = dex.stringSection.contains(accessRefusal) && dex.stringSection.contains(killSwitch)
            for (candidate in dex.classes) {
                if (candidate.type in sameKeyProviderTypes || candidate.superclass == SAME_KEY_PROVIDER ||
                    candidate.type in otherPolicyTypes) found += ImmutableClassDef.of(candidate)
                if (searchAnchors) candidate.methods.filter { method ->
                    method.returnType == "V" && method.parameterTypes.isEmpty() &&
                        !AccessFlags.STATIC.isSet(method.accessFlags) &&
                        method.strings().containsAll(listOf(accessRefusal, killSwitch)) &&
                        (method.calls().any { it.definingClass == TRUSTED_CALLER_PROVIDER &&
                            it.parameterTypes.isEmpty() && it.returnType.startsWith("L") } ||
                            // 454 inlines the policy getter, so the gate reads SameKey's field itself.
                            method.implementation?.instructions?.any {
                                ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == SAME_KEY_PROVIDER
                            } == true)
                }.forEach { inlineAnchors += ImmutableMethod.of(it) }
            }
        }
        NativeProviders(found, inlineAnchors)
    }

    private fun otherFixtures(check: (String, NativeProviders) -> Unit) {
        for (base in Fixtures.otherBuilds()) check(base.parentFile.name, nativeProviders(base))
    }

    private fun fixtures(check: (String, NativeProviders) -> Unit) {
        val version = AppCompatibilities.INSTAGRAM_TARGET_VERSION
        val bundles = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }
        assertTrue("the supported build must have a native fixture", bundles.isNotEmpty())
        bundles.forEach { check(it.name, nativeProviders(it)) }
    }

    private fun mutableTarget(classes: Iterable<ClassDef>, target: ProviderDecision): MutableMethod {
        val context = PatchContexts.of(classes.toList())
        return context.mutableClassDefBy(target.method.definingClass).methods.single {
            it.toString() == target.method.toString()
        }
    }

    @Test
    fun `both provider decisions retain native yes and conditional native refusal`() {
        val targets = sameKeyProviderDecisions(classes())
        assertEquals(2, targets.size)
        targets.forEach { verifyInjection(it) }
    }

    @Test
    fun `this above v15 is copied into the existing result register`() {
        val targets = sameKeyProviderDecisions(classes(guards = listOf(
            guard("queryGuard", "readPolicy", registers = 20),
            guard("callGuard", "writePolicy", registers = 20),
        )))
        targets.forEach { verifyInjection(it) }
    }

    @Test
    fun `missing or duplicate roles and additional SameKey providers stop discovery`() {
        val complete = classes()
        for (type in sameKeyProviderTypes) {
            assertThrows(PatchException::class.java) { sameKeyProviderDecisions(complete.filter { it.type != type }) }
            assertThrows(PatchException::class.java) { sameKeyProviderDecisions(complete + complete.single { it.type == type }) }
        }
        assertThrows(PatchException::class.java) {
            sameKeyProviderDecisions(complete + classDef("Lfixture/OtherProvider;", SAME_KEY_PROVIDER))
        }
        assertThrows(PatchException::class.java) {
            sameKeyProviderDecisions(complete.filter { it.type != SAME_KEY_PROVIDER } +
                classDef(SAME_KEY_PROVIDER, "Lcom/facebook/secure/content/delegate/ThirdPartyContentProviderDelegate;"))
        }
    }

    @Test
    fun `missing duplicate or inconsistent native guards stop discovery`() {
        val read = guard("queryGuard", "readPolicy")
        val write = guard("callGuard", "writePolicy")
        for (guards in listOf(listOf(read), listOf(read, guard("anotherQueryGuard", "readPolicy"), write),
            listOf(read, guard("callGuard", "writePolicy", checker = "anotherCheck")))) {
            assertThrows(PatchException::class.java) { sameKeyProviderDecisions(classes(guards = guards)) }
        }
    }

    @Test
    fun `changed policy fields or singleton assignments stop discovery`() {
        assertThrows(PatchException::class.java) {
            sameKeyProviderDecisions(classes(getters = listOf(getter("readPolicy", "read"))))
        }
        assertThrows(PatchException::class.java) {
            sameKeyProviderDecisions(classes(getters = listOf(getter("readPolicy", "read"), getter("writePolicy", "read"))))
        }
        for (assignments in listOf("", "iput-object v0, p0, $SAME_KEY_PROVIDER->other:$policy",
            "iput-object p1, p0, $SAME_KEY_PROVIDER->write:$policy")) {
            assertThrows(PatchException::class.java) { sameKeyProviderDecisions(classes(assignments = assignments)) }
        }
    }

    @Test
    fun `a result that aliases context or cannot encode instanceof stops discovery`() {
        for (result in listOf(1, 16)) {
            assertThrows(PatchException::class.java) {
                sameKeyProviderDecisions(classes(guards = listOf(
                    guard("queryGuard", "readPolicy", result = result, registers = 20),
                    guard("callGuard", "writePolicy"),
                )))
            }
        }
    }

    @Test
    fun `the supported native fixture has one SameKey role and two uniquely matched decisions`() = fixtures(::nativeCheck1)

    @Test
    fun `the supported native fixture has one SameKey role and two uniquely matched decisions on every other build too`() = otherFixtures(::nativeCheck1)

    @Test
    fun `native body proof rejects absent repeated wrong-context wrong-result and other-policy repairs`() = fixtures(::nativeCheck2)

    @Test
    fun `native body proof rejects absent repeated wrong-context wrong-result and other-policy repairs on every other build too`() = otherFixtures(::nativeCheck2)

    @Test
    fun `native discovery refuses missing duplicate wrong-context wrong-result and relocated policy checks`() = fixtures(::nativeCheck3)

    @Test
    fun `native discovery refuses missing duplicate wrong-context wrong-result and relocated policy checks on every other build too`() = otherFixtures(::nativeCheck3)

    private fun nativeCheck1(label: String, native: NativeProviders) {
        assertEquals(label, sameKeyProviderTypes + otherPolicyTypes, native.classes.map { it.type }.toSet())
        val targets = sameKeyProviderDecisions(native.classes)
        assertEquals(label, 2, targets.size)
        val inline = targets.single { killSwitch in it.method.strings() }
        assertEquals("inline anchors and kept policy call are globally unique in ${label}", 1, native.inlineAnchors.size)
        assertEquals(inline.method.toString(), native.inlineAnchors.single().toString())

        val context = PatchContexts.of(native.classes)
        val before = native.classes.flatMap { it.methods }.associate { it.toString() to snapshot(it) }
        targets.forEach { target ->
            val method = context.mutableClassDefBy(target.method.definingClass).methods.single {
                it.toString() == target.method.toString()
            }
            method.repairSameKeyProviderDecision(target)
            verifyInjection(target, method, before.getValue(target.method.toString()))
        }
        val changed = targets.map { it.method.toString() }.toSet()
        for (type in native.classes.map { it.type }) for (method in context.mutableClassDefBy(type).methods) {
            if (method.toString() !in changed) {
                assertEquals("native initialization, kill switch and other policies stay intact in $method",
                    before.getValue(method.toString()), snapshot(method))
            }
        }

        val base = native.classes.single { it.type == PROVIDER_BASE }
        val initialization = inline.method.calls().first()
        assertEquals(PROVIDER_BASE, initialization.definingClass)
        assertEquals("V", initialization.returnType)
        assertTrue(initialization.parameterTypes.isEmpty())
        val queryHelper = targets.single { it != inline }.method
        val queryEntry = base.methods.single { method ->
            killSwitch in method.strings() && method.calls().any { it.toString() == queryHelper.toString() }
        }
        for (gate in listOf(inline.method, queryEntry)) {
            assertEquals("both gates keep the same native initialization", initialization, gate.calls().first())
            assertTrue("same-process Binder checks survive", gate.calls().map { it.toString() }.containsAll(listOf(
                "Landroid/os/Binder;->getCallingUid()I", "Landroid/os/Process;->myUid()I",
                "Landroid/os/Binder;->getCallingPid()I", "Landroid/os/Process;->myPid()I",
            )))
            assertTrue("the native kill switch is present", killSwitch in gate.strings())
        }
    }

    private fun nativeCheck2(label: String, native: NativeProviders) {
        for (target in sameKeyProviderDecisions(native.classes)) {
            val original = snapshot(target.method)
            val mutants: List<(MutableMethod) -> Unit> = listOf(
                { it.removeInstructions(target.index, 6) },
                { it.addInstructionsWithLabels(target.index + 4, """
                    invoke-static/range { v${target.context} .. v${target.context} }, $hook
                """) },
                { it.replaceInstruction(target.index + 4, """
                    invoke-static/range { v${target.context + 1} .. v${target.context + 1} }, $hook
                """) },
                { it.replaceInstruction(target.index + 5, "move-result v${target.result + 1}") },
                { it.replaceInstruction(target.index + 2, """
                    instance-of v${target.result}, v${target.result}, Lcom/facebook/secure/content/delegate/ThirdPartyContentProviderDelegate;
                """) },
            )
            for ((index, mutate) in mutants.withIndex()) {
                val method = mutableTarget(native.classes, target)
                method.repairSameKeyProviderDecision(target)
                mutate(method)
                assertThrows("native guard mutant $index in ${target.method}", AssertionError::class.java) {
                    verifyInjection(target, method, original)
                }
            }
        }
    }

    private fun nativeCheck3(label: String, native: NativeProviders) {
        for (target in sameKeyProviderDecisions(native.classes)) {
            val original = target.method
            val implementation = original.implementation!!
            val code = implementation.instructions.toList()
            val checkerIndex = target.index - 2
            val checker = code[checkerIndex] as FiveRegisterInstruction
            val checkerRef = (code[checkerIndex] as ReferenceInstruction).reference
            fun copyMethod(body: List<Instruction>, name: String = original.name, owner: String = original.definingClass) =
                ImmutableMethod(owner, name, original.parameters, original.returnType, original.accessFlags,
                    original.annotations, original.hiddenApiRestrictions,
                    ImmutableMethodImplementation(implementation.registerCount, body,
                        implementation.tryBlocks, implementation.debugItems))
            fun replaceClassMethods(type: String, methods: Iterable<Method>): List<ClassDef> = native.classes.map { candidate ->
                if (candidate.type != type) candidate else ImmutableClassDef(candidate.type, candidate.accessFlags,
                    candidate.superclass, candidate.interfaces, candidate.sourceFile, candidate.annotations,
                    candidate.fields, methods)
            }
            val baseMethods = native.classes.single { it.type == PROVIDER_BASE }.methods.toList()
            fun replaced(replacement: Method) = replaceClassMethods(PROVIDER_BASE,
                baseMethods.map { if (it.toString() == original.toString()) replacement else it })

            val absent = code.take(checkerIndex) + List(3) { ImmutableInstruction10x(Opcode.NOP) } + code.drop(checkerIndex + 1)
            val wrongContext = code.toMutableList().apply {
                this[checkerIndex] = ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2,
                    checker.registerD, checker.registerC, 0, 0, 0, checkerRef)
            }
            val wrongResult = code.toMutableList().apply {
                this[target.index] = ImmutableInstruction21t(code[target.index].opcode, target.context,
                    (code[target.index] as OffsetInstruction).codeOffset)
            }
            val noGuard = replaceClassMethods(PROVIDER_BASE, baseMethods.filter { it.toString() != original.toString() })
            val thirdParty = native.classes.single { it.type in otherPolicyTypes && it.type.endsWith("ThirdPartyContentProviderDelegate;") }
            val moved = noGuard.map { candidate ->
                if (candidate.type != thirdParty.type) candidate else ImmutableClassDef(candidate.type, candidate.accessFlags,
                    candidate.superclass, candidate.interfaces, candidate.sourceFile, candidate.annotations,
                    candidate.fields, candidate.methods.toList() + copyMethod(code, owner = thirdParty.type))
            }
            val mutants = listOf(replaced(copyMethod(absent)), replaced(copyMethod(wrongContext)),
                replaced(copyMethod(wrongResult)), replaceClassMethods(PROVIDER_BASE,
                    baseMethods + copyMethod(code, name = "duplicateProviderDecision")), moved)
            for ((index, mutant) in mutants.withIndex()) {
                assertThrows("native discovery mutant $index in $original", PatchException::class.java) {
                    sameKeyProviderDecisions(mutant)
                }
            }
        }
    }
}

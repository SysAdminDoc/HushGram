/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

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

/** Hide You may also like in search, a switch of Hide the Explore grid: Instagram's check, its two builders, found and hooked. */
class HideSearchRecommendationsHookTest {
    private val check = "Lfixture/RecommendationCheck;"
    private val unit = "Lfixture/ResultsUnit;"
    private val lambda = "Lfixture/ResultsLambda;"
    private val page = "Lfixture/ResultsPage;"
    private val otherPage = "Lfixture/OtherResultsPage;"
    private val timing = "Lfixture/ResultsTiming;"
    private val predicate = "$check->A02($unit)Z"
    private val builderParameters = listOf("Landroid/content/Context;", "Ljava/util/List;", "Z")
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
    private val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

    /** The hook the patch writes is in the SearchRecommendations the bundle ships, public and static, and the reader has its stub. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(SEARCH_RECOMMENDATIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RECOMMENDATION_UNITS is not in the extension: $declared", RECOMMENDATION_UNITS.substringAfter("->") in declared)
        val stub = ExtensionDex.classDef(SEARCH_RECOMMENDATION_READER).methods.single { it.name == IS_RECOMMENDATION }
        assertTrue("static", AccessFlags.STATIC.isSet(stub.accessFlags))
        assertEquals(IS_RECOMMENDATION_SHAPE, "(${stub.parameterTypes.joinToString("")})${stub.returnType}")
    }

    /** The lambda and the builder start by filtering their list, the timing logger is left alone, and the stub asks Instagram's check. */
    @Test
    fun bothBuildersAreHookedAndTheStubFilled() {
        val stock = classes()
        val context = PatchContexts.of(stock)
        val lambdaBefore = stock.single { it.type == lambda }.methods.single().code()
        val builderBefore = stock.single { it.type == page }.methods.single().code()
        val timingBefore = stock.single { it.type == timing }.methods.single().code()

        val sites = context.findSearchRecommendations()
        context.hideSearchRecommendations(sites)

        assertEquals("the check", predicate, sites.predicate)
        assertEquals("the unit", unit, sites.unitType)
        val hookedLambda = context.mutableClassDefBy(lambda).methods.single()
        assertListFiltered("stand-in lambda", hookedLambda, hookedLambda.implementation!!.registerCount - 1, castBack = false)
        assertEquals("Instagram's lambda after the filter", lambdaBefore.map { it.opcode }, hookedLambda.code().drop(2).map { it.opcode })
        val hookedBuilder = context.mutableClassDefBy(page).methods.single()
        assertListFiltered("stand-in builder", hookedBuilder, hookedBuilder.implementation!!.registerCount - 2, castBack = true)
        assertEquals("Instagram's builder after the filter", builderBefore.map { it.opcode }, hookedBuilder.code().drop(3).map { it.opcode })
        assertEquals("the logger", timingBefore.map { it.opcode }, context.mutableClassDefBy(timing).methods.single().code().map { it.opcode })
        assertStubFilled("stand-in", context.stub(), predicate)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val where = "$check->A02"
        val cases = listOf(
            classes(checks = 0) to "expected one static check of a results unit loading $END_OF_SERP_RECOMMENDATION, found 0",
            classes(checks = 2) to "expected one static check of a results unit loading $END_OF_SERP_RECOMMENDATION, found 2",
            classes(unitPublic = false) to "$where or its unit $unit isn't public",
            classes(reader = false) to "the extension has no $SEARCH_RECOMMENDATION_READER",
            classes(lambdas = 0) to "expected one lambda asking $where, found 0",
            classes(lambdaBranchesFirst = true) to "$lambda->invoke doesn't cast its second argument to a list first",
            classes(builders = 0) to "expected one static builder taking a list and asking $where, found 0",
            classes(builders = 2) to "expected one static builder taking a list and asking $where, found 2",
            classes(loopsToStart = true) to "something in $page->A00 jumps back to its start",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideSearchRecommendations(context.findSearchRecommendations())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == RECOMMENDATION_UNITS || it.referenceText() == predicate && method.definingClass == SEARCH_RECOMMENDATION_READER } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In every declared 454 build both builders are there once, hooked at their start, and the stub asks the build's own check. */
    @Test
    fun eachDeclaredBuildHooksBothBuilders() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every declared build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val classes = realClasses(bundle)
            val context = PatchContexts.of(classes)

            val sites = context.findSearchRecommendations()
            context.hideSearchRecommendations(sites)

            val hookedLambda = context.mutableClassDefBy(sites.lambdaType).methods.single {
                it.name == LAMBDA_INVOKE && "(${it.parameterTypes.joinToString("")})${it.returnType}" == LAMBDA_SHAPE
            }
            assertListFiltered(bundle.name, hookedLambda, hookedLambda.implementation!!.registerCount - 1, castBack = false)
            val hookedBuilder = context.mutableClassDefBy(sites.builderType).methods.single {
                it.name == sites.builderName && it.parameterTypes.map(CharSequence::toString) == sites.builderParameters
            }
            assertEquals("${bundle.name}: one list", 1, sites.builderParameters.count { it == "Ljava/util/List;" })
            assertListFiltered(bundle.name, hookedBuilder, sites.builderRegister, castBack = true)
            assertStubFilled(bundle.name, context.stub(), sites.predicate)
            assertEquals(
                "${bundle.name}: methods calling $RECOMMENDATION_UNITS", 2,
                classes.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == RECOMMENDATION_UNITS } } },
            )
        }
    }

    /** First thing: the list's register handed to the extension, its answer put back in it, and for a list parameter cast back to one. */
    private fun assertListFiltered(what: String, method: Method, list: Int, castBack: Boolean) {
        val code = method.code()
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$what: the hook", RECOMMENDATION_UNITS, code[0].referenceText())
        assertEquals("$what: the list", list, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: back in the list's register", list, (code[1] as OneRegisterInstruction).registerA)
        if (castBack) {
            assertEquals("$what: cast back", Opcode.CHECK_CAST, code[2].opcode)
            assertEquals("$what: cast back's register", list, (code[2] as OneRegisterInstruction).registerA)
            assertEquals("$what: to a list", "Ljava/util/List;", (code[2].reference() as TypeReference).type)
        }
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == RECOMMENDATION_UNITS })
    }

    /** The stub asks the check only of a unit of its class, and answers what it says. */
    private fun assertStubFilled(what: String, stub: Method, predicate: String) {
        val code = stub.code()
        assertEquals("$what: the stub starts with the class test", Opcode.INSTANCE_OF, code[0].opcode)
        assertEquals("$what: of the unit's class", predicate.substringAfter("(").substringBefore(")"), (code[0].reference() as TypeReference).type)
        assertEquals("$what: the stub asks the check once", 1, code.count { it.referenceText() == predicate })
        assertTrue("$what: and answers it", code.any { it.opcode == Opcode.RETURN })
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.stub(): Method =
        mutableClassDefBy(SEARCH_RECOMMENDATION_READER).methods.single { it.name == IS_RECOMMENDATION }

    /**
     * Shaped like 454's: a public check of a results unit loading the marker, a merged lambda whose
     * `invoke` casts its second argument to a list and asks it, a static builder taking the list and
     * asking it, and a timing logger asking it with no list, plus the extension's reader.
     */
    private fun classes(
        checks: Int = 1,
        unitPublic: Boolean = true,
        reader: Boolean = true,
        lambdas: Int = 1,
        lambdaBranchesFirst: Boolean = false,
        builders: Int = 1,
        loopsToStart: Boolean = false,
    ): List<ClassDef> {
        val checkBody = { marker: String ->
            """
                const-string v0, "$marker"
                invoke-virtual {p0}, $unit->A00()Ljava/lang/String;
                move-result-object v1
                invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                return v0
            """
        }
        val checkMethods = when (checks) {
            0 -> listOf(method(check, "A02", listOf(unit), "Z", 2, static, checkBody("end_of_serp_header")))
            else -> List(checks) { method(check, if (it == 0) "A02" else "A03", listOf(unit), "Z", 2, static, checkBody(END_OF_SERP_RECOMMENDATION)) }
        }
        val asks = """
            move-result-object v1
            check-cast v1, $unit
            invoke-static {v1}, $predicate
            move-result v1
        """
        val invoke = method(
            lambda, LAMBDA_INVOKE, listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;", 2,
            AccessFlags.PUBLIC.value or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value,
            listOfNotNull(
                if (lambdaBranchesFirst) "if-eqz p1, :done" else null,
                "move-object v0, p2",
                "check-cast v0, Ljava/util/List;",
                "const/4 v1, 0x0",
                "invoke-interface {v0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;",
                asks,
                ":done",
                "return-object p1",
            ).joinToString("\n"),
        )
        val builder = { owner: String ->
            method(
                owner, "A00", builderParameters, "Lfixture/Screen;", 2, static,
                listOfNotNull(
                    if (loopsToStart) ":start" else null,
                    "const/4 v0, 0x0",
                    "invoke-interface {p1, v0}, Ljava/util/List;->get(I)Ljava/lang/Object;",
                    asks,
                    if (loopsToStart) "if-eqz p2, :start" else null,
                    "const/4 v0, 0x0",
                    "return-object v0",
                ).joinToString("\n"),
            )
        }
        val logger = method(
            timing, "A00", listOf(unit, "I"), "V", 1, static,
            """
                invoke-static {p0}, $predicate
                move-result v0
                return-void
            """,
        )
        return listOfNotNull(
            classDef(check, checkMethods),
            classDef(unit, emptyList(), if (unitPublic) instance else AccessFlags.FINAL.value),
            if (lambdas > 0) classDef(lambda, listOf(invoke)) else null,
            if (builders > 0) classDef(page, listOf(builder(page))) else null,
            if (builders > 1) classDef(otherPage, listOf(builder(otherPage))) else null,
            classDef(timing, listOf(logger)),
            if (reader) ExtensionDex.classDef(SEARCH_RECOMMENDATION_READER) else null,
        )
    }

    /**
     * The real build's classes the patch reads: the ones loading the marker (pools included), every
     * class calling a static check of theirs that answers a boolean about one argument, those
     * arguments' classes, and the extension's reader.
     */
    private fun realClasses(bundle: File): List<ClassDef> {
        val loading = FixtureDex.classesLoading(bundle, END_OF_SERP_RECOMMENDATION)
        val checks = loading.flatMap { holder ->
            holder.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.size == 1 }
        }
        val have = loading.mapTo(HashSet()) { it.type }
        val callers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.methodSection.none { reference -> checks.any { reference.isCallTo(it) } }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.type in have) continue
                val calls = classDef.methods.any { method ->
                    method.code().any { instruction -> (instruction.reference() as? MethodReference)?.let { reference -> checks.any { reference.isCallTo(it) } } == true }
                }
                if (calls) {
                    callers += ImmutableClassDef.of(classDef)
                    have += classDef.type
                }
            }
        }
        val units = checks.map { it.parameterTypes.single().toString() }.filterTo(HashSet()) { it !in have }
        val found = loading + callers + FixtureDex.classes(bundle, units).values
        return FixtureDex.withStringPools(bundle, found) + ExtensionDex.classDef(SEARCH_RECOMMENDATION_READER)
    }

    private fun MethodReference.isCallTo(method: Method): Boolean =
        definingClass == method.definingClass && name == method.name && returnType == method.returnType &&
            parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() + if (AccessFlags.STATIC.isSet(flags)) 0 else 1
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, flags: Int = instance): ClassDef =
        ImmutableClassDef(type, flags, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = (reference() as? MethodReference)?.toString()
}

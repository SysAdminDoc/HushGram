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
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
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
    private val reducer = "Lfixture/ResultsReducer;"
    private val otherReducer = "Lfixture/OtherResultsReducer;"
    private val feedPage = "Lfixture/ResultsFeedPage;"
    private val state = "Lfixture/ResultsState;"
    private val pageUnits = "$feedPage->A0B:Ljava/util/List;"
    private val kind = "Lfixture/UnitKind;"
    private val kindGetter = "$unit->A01()$kind"
    private val headerKind = "$kind->A05:$kind"
    private val predicate = "$check->A02($unit)Z"
    private val builderParameters = listOf("Landroid/content/Context;", "Ljava/util/List;", "Z")
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
    private val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

    private companion object {
        /** What the patch puts in front of the page's answer, its `nop` label included. */
        const val PAGE_HOOK = 7
    }

    /** The hook the patch writes is in the SearchRecommendations the bundle ships, public and static, and the reader has its stub. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(SEARCH_RECOMMENDATIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$RECOMMENDATION_UNITS is not in the extension: $declared", RECOMMENDATION_UNITS.substringAfter("->") in declared)
        assertTrue("$LAST_PAGE is not in the extension: $declared", LAST_PAGE.substringAfter("->") in declared)
        for (name in listOf(IS_RECOMMENDATION, IS_HEADER)) {
            val stub = ExtensionDex.classDef(SEARCH_RECOMMENDATION_READER).methods.single { it.name == name }
            assertTrue("$name static", AccessFlags.STATIC.isSet(stub.accessFlags))
            assertEquals(name, IS_RECOMMENDATION_SHAPE, "(${stub.parameterTypes.joinToString("")})${stub.returnType}")
        }
    }

    /**
     * The lambda and the builder start by filtering their list, the page's answer to whether more
     * come after it starts by asking about its units, the timing logger and the reducer are left
     * alone, and the stub asks Instagram's check.
     */
    @Test
    fun bothBuildersAreHookedAndTheStubFilled() {
        val stock = classes()
        val context = PatchContexts.of(stock)
        val lambdaBefore = stock.single { it.type == lambda }.methods.single().code()
        val builderBefore = stock.single { it.type == page }.methods.single().code()
        val timingBefore = stock.single { it.type == timing }.methods.single().code()
        val reducerBefore = stock.single { it.type == reducer }.methods.single().code()
        val moreBefore = stock.single { it.type == feedPage }.methods.single { it.name == "EIF" }.code()

        val sites = context.findSearchRecommendations()
        context.hideSearchRecommendations(sites)

        assertEquals("the check", predicate, sites.predicate)
        assertEquals("the unit", unit, sites.unitType)
        assertEquals("the unit's kind", kindGetter, sites.kindGetter)
        assertEquals("the title kind", headerKind, sites.headerKind)
        val hookedLambda = context.mutableClassDefBy(lambda).methods.single()
        assertListFiltered("stand-in lambda", hookedLambda, hookedLambda.implementation!!.registerCount - 1, castBack = false)
        assertEquals("Instagram's lambda after the filter", lambdaBefore.map { it.opcode }, hookedLambda.code().drop(2).map { it.opcode })
        val hookedBuilder = context.mutableClassDefBy(page).methods.single()
        assertListFiltered("stand-in builder", hookedBuilder, hookedBuilder.implementation!!.registerCount - 2, castBack = true)
        assertEquals("Instagram's builder after the filter", builderBefore.map { it.opcode }, hookedBuilder.code().drop(3).map { it.opcode })
        assertEquals("the logger", timingBefore.map { it.opcode }, context.mutableClassDefBy(timing).methods.single().code().map { it.opcode })
        assertEquals("the reducer", reducerBefore.map { it.opcode }, context.mutableClassDefBy(reducer).methods.single().code().map { it.opcode })
        assertEquals("the page", feedPage, sites.pageType)
        assertEquals("its answer", "EIF", sites.moreName)
        assertEquals("its units", pageUnits, sites.pageUnits)
        val hookedMore = context.mutableClassDefBy(feedPage).methods.single { it.name == sites.moreName }
        assertPageAsked("stand-in page", hookedMore, sites)
        assertEquals("Instagram's answer after the question", moreBefore.map { it.opcode }, hookedMore.code().drop(PAGE_HOOK).map { it.opcode })
        assertEquals("the page's other answer", 1, context.mutableClassDefBy(feedPage).methods.count { it.code().any { i -> i.referenceText() == LAST_PAGE } })
        assertStubFilled("stand-in", context.stub(), predicate)
        assertHeaderStubFilled("stand-in", context.headerStub(), sites)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val where = "$check->A02"
        val cases = listOf(
            classes(checks = 0) to "expected one static check of a results unit loading $END_OF_SERP_RECOMMENDATION, found 0",
            classes(checks = 2) to "expected one static check of a results unit loading $END_OF_SERP_RECOMMENDATION, found 2",
            classes(unitPublic = false) to "$where or its unit $unit isn't public",
            classes(readsKind = false) to "expected $where to read one kind of its unit, found 0",
            classes(header = false) to "expected one $HEADER_KIND kind in $kind, found 0",
            classes(reader = false) to "the extension has no $SEARCH_RECOMMENDATION_READER",
            classes(lambdas = 0) to "expected one lambda asking $where, found 0",
            classes(lambdaBranchesFirst = true) to "$lambda->invoke doesn't cast its second argument to a list first",
            classes(builders = 0) to "expected one static builder taking a list and asking $where, found 0",
            classes(builders = 2) to "expected one static builder taking a list and asking $where, found 2",
            classes(loopsToStart = true) to "something in $page->A00 jumps back to its start",
            classes(reducers = 0) to "expected one static reducer asking $where that hands back the state it's given, found 0",
            classes(reducers = 2) to "expected one static reducer asking $where that hands back the state it's given, found 2",
            classes(questions = 2) to "expected $reducer->A00 to ask its page one yes or no question, found 2",
            classes(readsUnits = false) to "expected $reducer->A00 to read one list of $feedPage, found 0",
            classes(moreFinal = false) to "$feedPage->EIF()Z isn't final",
            classes(moreLocals = 0) to "$feedPage->EIF()Z has no register of its own",
            classes(moreLocals = 16) to "$feedPage->EIF()Z keeps this past v15",
            classes(moreLoops = true) to "something in $feedPage->EIF jumps back to its start",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideSearchRecommendations(context.findSearchRecommendations())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method ->
                    method.code().any {
                        it.referenceText() == RECOMMENDATION_UNITS || it.referenceText() == LAST_PAGE ||
                            it.referenceText() == predicate && method.definingClass == SEARCH_RECOMMENDATION_READER
                    }
                }
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
            assertHeaderStubFilled(bundle.name, context.headerStub(), sites)
            assertEquals(
                "${bundle.name}: methods calling $RECOMMENDATION_UNITS", 2,
                classes.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == RECOMMENDATION_UNITS } } },
            )
            val hookedMore = context.mutableClassDefBy(sites.pageType).methods.single {
                it.name == sites.moreName && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
            assertPageAsked(bundle.name, hookedMore, sites)
            assertEquals(
                "${bundle.name}: methods calling $LAST_PAGE", 1,
                classes.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == LAST_PAGE } } },
            )
        }
    }

    /**
     * First thing: the page's units read into its first local and handed to the extension, its
     * answer tested, and a no returned when it says this is the last page; Instagram's code after.
     */
    private fun assertPageAsked(what: String, method: Method, sites: SearchRecommendationSites) {
        val code = method.code()
        val self = method.implementation!!.registerCount - 1
        assertEquals("$what: the units", Opcode.IGET_OBJECT, code[0].opcode)
        assertEquals("$what: the page's list", sites.pageUnits, code[0].reference().toString())
        assertEquals("$what: into the first local", 0, (code[0] as TwoRegisterInstruction).registerA)
        assertEquals("$what: of this", self, (code[0] as TwoRegisterInstruction).registerB)
        assertEquals("$what: the question", Opcode.INVOKE_STATIC, code[1].opcode)
        assertEquals("$what: the hook", LAST_PAGE, code[1].referenceText())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[2].opcode)
        assertEquals("$what: tested", Opcode.IF_EQZ, code[3].opcode)
        assertEquals("$what: no", Opcode.CONST_4, code[4].opcode)
        assertEquals("$what: answered", Opcode.RETURN, code[5].opcode)
        for (at in listOf(2, 3, 4, 5)) assertEquals("$what: [$at] in the first local", 0, (code[at] as OneRegisterInstruction).registerA)
        val address = code.runningFold(0) { start, instruction -> start + instruction.codeUnits }
        assertEquals("$what: then Instagram's own answer", Opcode.NOP, code[PAGE_HOOK - 1].opcode)
        assertEquals("$what: reached when it's not the last", address[PAGE_HOOK - 1], address[3] + (code[3] as OffsetInstruction).codeOffset)
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == LAST_PAGE })
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

    /** The title stub reads the unit's kind once, of a unit of its class, and compares it with the HEADER constant. */
    private fun assertHeaderStubFilled(what: String, stub: Method, sites: SearchRecommendationSites) {
        val code = stub.code()
        val test = code.indexOfFirst { it.opcode == Opcode.INSTANCE_OF }
        assertTrue("$what: the title stub tests the class", test >= 0)
        assertEquals("$what: of the unit's class", sites.unitType, (code[test].reference() as TypeReference).type)
        assertEquals("$what: reads the kind once", 1, code.count { it.opcode == Opcode.INVOKE_VIRTUAL && it.referenceText() == sites.kindGetter })
        assertEquals("$what: against HEADER", 1, code.count { it.opcode == Opcode.SGET_OBJECT && it.reference().toString() == sites.headerKind })
        assertTrue("$what: compares them", code.any { it.opcode == Opcode.IF_NE })
        assertTrue("$what: and answers", code.any { it.opcode == Opcode.RETURN })
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.stub(): Method =
        mutableClassDefBy(SEARCH_RECOMMENDATION_READER).methods.single { it.name == IS_RECOMMENDATION }

    private fun app.morphe.patcher.patch.BytecodePatchContext.headerStub(): Method =
        mutableClassDefBy(SEARCH_RECOMMENDATION_READER).methods.single { it.name == IS_HEADER }

    /**
     * Shaped like 454's: a public check of a results unit that reads the unit's kind (an enum with a
     * HEADER and a MEDIA_GRID constant) and then loads the marker, a merged lambda whose `invoke`
     * casts its second argument to a list and asks it, a static builder taking the list and asking
     * it, a timing logger asking it with no list, a static reducer asking it that reads a page's
     * units, asks the page whether more come after it and hands back the state it was given, the
     * abstract page with that final answer, plus the extension's reader.
     */
    private fun classes(
        checks: Int = 1,
        unitPublic: Boolean = true,
        readsKind: Boolean = true,
        header: Boolean = true,
        reader: Boolean = true,
        lambdas: Int = 1,
        lambdaBranchesFirst: Boolean = false,
        builders: Int = 1,
        loopsToStart: Boolean = false,
        reducers: Int = 1,
        questions: Int = 1,
        readsUnits: Boolean = true,
        moreFinal: Boolean = true,
        moreLocals: Int = 1,
        moreLoops: Boolean = false,
    ): List<ClassDef> {
        val checkBody = { marker: String ->
            listOfNotNull(
                if (readsKind) "invoke-virtual {p0}, $kindGetter" else null,
                if (readsKind) "move-result-object v0" else null,
                if (readsKind) "sget-object v1, $kind->A07:$kind" else null,
                if (readsKind) "if-ne v0, v1, :no" else null,
                "const-string v0, \"$marker\"",
                "invoke-virtual {p0}, $unit->A00()Ljava/lang/String;",
                "move-result-object v1",
                "invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
                "move-result v0",
                "return v0",
                ":no",
                "const/4 v0, 0x0",
                "return v0",
            ).joinToString("\n")
        }
        val kinds = listOfNotNull(if (header) "A05" to HEADER_KIND else null, "A07" to "MEDIA_GRID")
        val kindSetup = method(
            kind, "<clinit>", emptyList(), "V", 3, AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
            kinds.withIndex().joinToString("\n") { (ordinal, constant) ->
                """
                    new-instance v0, $kind
                    const-string v1, "${constant.second}"
                    const/4 v2, $ordinal
                    invoke-direct {v0, v1, v2}, $kind-><init>(Ljava/lang/String;I)V
                    sput-object v0, $kind->${constant.first}:$kind
                """
            } + "\nreturn-void",
        )
        val kindFields = kinds.map { (field, _) ->
            ImmutableField(kind, field, kind, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value or AccessFlags.ENUM.value, null, null, null)
        }
        val getter = method(
            unit, "A01", emptyList(), kind, 1, instance,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
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
        val reduce = { owner: String ->
            method(
                owner, "A00", listOf(feedPage, state), state, 2, static,
                listOfNotNull(
                    if (readsUnits) "iget-object v0, p0, $pageUnits" else "const/4 v0, 0x0",
                    "const/4 v1, 0x0",
                    "invoke-interface {v0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;",
                    asks,
                    "invoke-virtual {p0}, $feedPage->EIF()Z",
                    "move-result v1",
                    if (questions > 1) "invoke-virtual {p0}, $feedPage->A0F()Z" else null,
                    if (questions > 1) "move-result v1" else null,
                    "return-object p1",
                ).joinToString("\n"),
            )
        }
        val pageAbstract = AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value
        val more = method(
            feedPage, "EIF", emptyList(), "Z", moreLocals, if (moreFinal) instance else AccessFlags.PUBLIC.value,
            when {
                moreLoops -> """
                    :start
                    iget-boolean v0, p0, $feedPage->A0E:Z
                    if-eqz v0, :start
                    return v0
                """
                moreLocals == 1 -> """
                    iget-boolean v0, p0, $feedPage->A0E:Z
                    return v0
                """
                else -> """
                    const/4 v0, 0x0
                    return v0
                """
            },
        )
        val otherQuestion = method(feedPage, "A0F", emptyList(), "Z", 1, instance, "const/4 v0, 0x1\nreturn v0")
        val pageFields = listOf(
            ImmutableField(feedPage, "A0B", "Ljava/util/List;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
            ImmutableField(feedPage, "A0E", "Z", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null),
        )
        return listOfNotNull(
            classDef(check, checkMethods),
            classDef(unit, listOf(getter), if (unitPublic) instance else AccessFlags.FINAL.value),
            classDef(kind, listOf(kindSetup), instance or AccessFlags.ENUM.value, "Ljava/lang/Enum;", kindFields),
            if (lambdas > 0) classDef(lambda, listOf(invoke)) else null,
            if (builders > 0) classDef(page, listOf(builder(page))) else null,
            if (builders > 1) classDef(otherPage, listOf(builder(otherPage))) else null,
            classDef(timing, listOf(logger)),
            if (reducers > 0) classDef(reducer, listOf(reduce(reducer))) else null,
            if (reducers > 1) classDef(otherReducer, listOf(reduce(otherReducer))) else null,
            classDef(feedPage, listOfNotNull(more, if (questions > 1) otherQuestion else null), pageAbstract, fields = pageFields),
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
        val unitTypes = checks.mapTo(HashSet()) { it.parameterTypes.single().toString() }
        val units = unitTypes.filterTo(HashSet()) { it !in have }
        // The kind the check reads off its unit: an enum, read for its HEADER constant.
        val kinds = checks.flatMap { it.code() }.mapNotNull { it.reference() as? MethodReference }
            .filter { it.definingClass in unitTypes && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
            .mapTo(HashSet()) { it.returnType }.filterTo(HashSet()) { it !in have && it !in unitTypes }
        // The arguments of a caller's static methods that hand back one of them: the reducer's page.
        val pages = callers.flatMap { it.methods }
            .filter { method -> AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.any { it.toString() == method.returnType } }
            .flatMap { method -> method.parameterTypes.map(CharSequence::toString) }
            .filterTo(HashSet()) { it.startsWith("L") && it !in have && it !in units && it !in kinds }
        val found = loading + callers + FixtureDex.classes(bundle, units + kinds + pages).values
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

    private fun classDef(
        type: String,
        methods: List<Method>,
        flags: Int = instance,
        superclass: String = "Ljava/lang/Object;",
        fields: List<ImmutableField> = emptyList(),
    ): ClassDef = ImmutableClassDef(type, flags, superclass, null, null, null, fields, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = (reference() as? MethodReference)?.toString()
}

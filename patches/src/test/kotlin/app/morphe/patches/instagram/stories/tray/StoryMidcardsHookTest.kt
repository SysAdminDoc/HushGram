/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The midcard half of Hide Suggested for you between stories (#154): each midcard build asks the extension. */
class StoryMidcardsHookTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val integer = "Ljava/lang/Integer;"
    private val list = "Ljava/util/List;"
    private val names = "Lfixture/MidcardNames;"
    private val reel = "Lfixture/Reel;"
    private val types = "Lfixture/MidcardTypes;"
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
    private val reelInit = "$reel-><init>(Lfixture/Owner;$integer$list$list)V"
    private val mapperRef = "$names->A00($integer)Ljava/lang/String;"
    private val helperRef = "Lfixture/MidcardHelper;->A1I($session$integer${list}I)V"

    /** The hook the patch writes is in the StoriesTray the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_MIDCARD.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$STORY_MIDCARD is not in the extension: $declared", STORY_MIDCARD.substringAfter("->") in declared)
    }

    /**
     * Each adder asks right after each build, with the type the reel took, and answers false or null
     * on a yes. An adder handing the type to a helper that builds and adds the reel, as the x86
     * 453.0.0.57.79 build does for two midcards, asks right before that call instead. The helper
     * itself, and the code rebuilding reels from their ids, which takes no list, stay as they were.
     */
    @Test
    fun eachAdderAsksRightAfterItsBuild() {
        val classes = classes()
        val context = PatchContexts.of(classes)
        val midcards = context.findStoryMidcards()
        assertEquals(mapperRef, midcards.mapper.toString())
        assertEquals(reelInit, midcards.reel.toString())
        assertEquals(listOf(helperRef), midcards.helpers.map { it.toString() })
        assertEquals(
            listOf(
                "Lfixture/SuggestedUsersAdder;->A03", "Lfixture/SuggestedUsersAsync;->A01", "Lfixture/Trending;->A00",
                "Lfixture/TrendingRange;->A00", "Lfixture/TwoCards;->A00",
            ),
            midcards.adders.map { "${it.type}->${it.name}" }.sorted(),
        )

        context.hideStoryMidcards(midcards)

        assertHooked("sync", context.mutableClassDefBy("Lfixture/SuggestedUsersAdder;").methods.single(), Opcode.RETURN, builds = 1)
        assertHooked("async", context.mutableClassDefBy("Lfixture/SuggestedUsersAsync;").methods.single(), Opcode.RETURN_OBJECT, builds = 1)
        assertHooked("two builds", context.mutableClassDefBy("Lfixture/TwoCards;").methods.single(), Opcode.RETURN, builds = 2)
        assertHooked("helper call", context.mutableClassDefBy("Lfixture/Trending;").methods.single(), Opcode.RETURN, builds = 0, helpers = listOf(helperRef), calls = 1)
        assertHooked("helper range call", context.mutableClassDefBy("Lfixture/TrendingRange;").methods.single(), Opcode.RETURN_OBJECT, builds = 0, helpers = listOf(helperRef), calls = 1)
        for (type in listOf("Lfixture/Rebuild;", "Lfixture/MidcardHelper;")) {
            val after = context.mutableClassDefBy(type).methods.single()
            assertEquals("$type changed", classes.single { it.type == type }.methods.single().code().map { it.opcode }, after.code().map { it.opcode })
        }
    }

    /** Before a helper call the type is the call's Integer operand, and the borrowed local is one the call doesn't read. */
    @Test
    fun theHelperCallsTypeIsItsIntegerOperand() {
        val context = PatchContexts.of(classes())
        context.hideStoryMidcards(context.findStoryMidcards())
        val code = context.mutableClassDefBy("Lfixture/Trending;").methods.single().code()
        val call = code.indexOfFirst { it.referenceText() == helperRef }
        // The fixture's call is {p0, v0, p1, p2} with v0 the type; v1 holds the true it answers and v2 is unused.
        assertEquals(0, (code[call - 7] as RegisterRangeInstruction).startRegister)
        assertEquals(2, (code[call - 6] as OneRegisterInstruction).registerA)
    }

    /** The register the hook borrows is one nothing reads afterwards, never the reel or the list. */
    @Test
    fun theBorrowedRegisterIsFree() {
        val context = PatchContexts.of(classes())
        context.hideStoryMidcards(context.findStoryMidcards())
        val code = context.mutableClassDefBy("Lfixture/SuggestedUsersAdder;").methods.single().code()
        val build = code.indexOfFirst { it.referenceText() == reelInit }
        val borrowed = (code[build + 2] as OneRegisterInstruction).registerA
        val built = (code[build] as FiveRegisterInstruction).registerC
        assertFalse("borrowed the reel's register", borrowed == built)
        // The fixture's v0 holds the type and nothing reads it again, so it's the one borrowed.
        assertEquals(0, borrowed)
    }

    /** 453 has a second namer holding the midcard's name; the one the reel constructor asks is taken. */
    @Test
    fun theNamerTheReelAsksIsTaken() {
        val found = PatchContexts.of(classes(twoMappers = true)).findStoryMidcards()
        assertEquals("$names->A00", "${found.mapper.definingClass}->${found.mapper.name}")
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(name = "story_interstitial_something_else") to "expected a static (Integer)String holding \"$SUGGESTED_USERS_MIDCARD\", found none",
            classes(reelNames = false) to "holding \"$SUGGESTED_USERS_MIDCARD\" with one reel constructor (Object, Integer, List, List) asking it, found 0",
            classes(adders = false) to "nothing taking a list builds a midcard with $reel-><init>",
            classes(repository = "Lfixture/SomeOtherRepository;") to "none of the 5 midcard adder(s) asks $SUGGESTED_USERS_REPOSITORY",
            // A check put in front of a call something jumps to would be skipped on that path.
            classes(helperJump = true) to "Lfixture/Trending;->A00 jumps straight to its midcard helper call",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hideStoryMidcards(context.findStoryMidcards()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == STORY_MIDCARD } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the type namer, the reel constructor and the adders are found, one adder
     * asks the suggested users repository, and every build in every adder asks the extension.
     */
    @Test
    fun eachDeclaredBuildAsksAfterEachMidcardBuild() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        val hooksByBuild = linkedMapOf<String, Int>()
        for (bundle in bundles) {
            val holders = linkedMapOf<String, ClassDef>()
            val namers = HashSet<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == SUGGESTED_USERS_MIDCARD } }) {
                        holders[classDef.type] = ImmutableClassDef.of(classDef)
                        namers += classDef.type
                    }
                }
            }
            val reels = HashSet<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val builds = classDef.methods.any { m ->
                        m.name == "<init>" && m.parameterTypes.drop(1).map(CharSequence::toString) == listOf(integer, list, list) &&
                            m.code().any { (it.methodReference())?.definingClass in namers }
                    }
                    if (builds) {
                        holders[classDef.type] = ImmutableClassDef.of(classDef)
                        reels += classDef.type
                    }
                }
            }
            // The static void methods building a reel, which some builds outline a midcard build into.
            val outlined = HashSet<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val builders = classDef.methods.filter { m ->
                        m.code().any { instruction ->
                            instruction.methodReference()?.let {
                                it.definingClass in reels && it.name == "<init>" && it.parameterTypes.drop(1).map(CharSequence::toString) == listOf(integer, list, list)
                            } == true
                        }
                    }
                    if (builders.isNotEmpty() && classDef.type !in holders) holders[classDef.type] = ImmutableClassDef.of(classDef)
                    builders.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
                        .forEach { outlined += "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})V" }
                }
            }
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val calls = classDef.methods.any { m -> m.code().any { it.referenceText() in outlined } }
                    if (calls && classDef.type !in holders) holders[classDef.type] = ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders.values)

            val midcards = context.findStoryMidcards()
            context.hideStoryMidcards(midcards)

            assertTrue("${bundle.name}: the namer ${midcards.mapper}", midcards.mapper.definingClass in namers)
            assertTrue("${bundle.name}: the reel ${midcards.reel}", midcards.reel.definingClass in reels)
            val reelText = midcards.reel.toString()
            val helpers = midcards.helpers.map { it.toString() }
            assertTrue("${bundle.name}: a helper that isn't a static void reel builder in $helpers", outlined.containsAll(helpers))
            var hooks = 0
            for (site in midcards.adders) {
                val before = holders.getValue(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
                val after = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
                val answer = if (after.returnType == "Z") Opcode.RETURN else Opcode.RETURN_OBJECT
                val builds = before.code().count { it.referenceText() == reelText }
                val calls = before.code().count { it.referenceText() in helpers }
                assertHooked("${bundle.name}: ${site.type}->${site.name}", after, answer, builds, midcards.mapper.toString(), reelText, helpers, calls)
                hooks += builds + calls
            }
            // Keyed by folder as well, since every declared build but one is a base.apk.
            hooksByBuild["${bundle.parentFile.name}/${bundle.name}"] = hooks
            // On 451 that's eight adders, the async suggested users one among them, answering an object.
            val answers = midcards.adders.map { site ->
                holders.getValue(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }.returnType == "Z"
            }
            assertTrue("${bundle.name}: no boolean adder in ${midcards.adders.map { "${it.type}->${it.name}" }}", true in answers)
            assertTrue("${bundle.name}: no adder answering an object in ${midcards.adders.map { "${it.type}->${it.name}" }}", false in answers)
        }
        // Every build of a version is the same code compiled its own way, so each one hides as many
        // midcards. A build that inlines or outlines a midcard where the others don't shows up here.
        assertEquals("the midcard checks in each build: $hooksByBuild", 1, hooksByBuild.values.toSet().size)
    }

    /**
     * Right after each call to the reel constructor, and right before each call to one of [helpers]:
     * the namer handed the type the reel takes, its answer handed to the hook, and on a yes a zero
     * answered, false or null.
     */
    private fun assertHooked(
        what: String,
        method: Method,
        answer: Opcode,
        builds: Int,
        mapper: String = mapperRef,
        reelText: String = reelInit,
        helpers: List<String> = emptyList(),
        calls: Int = 0,
    ) {
        val code = method.code()
        val at = code.indices.filter { code[it].referenceText() == reelText }
        val helped = code.indices.filter { code[it].referenceText() in helpers }
        assertEquals("$what: builds", builds, at.size)
        assertEquals("$what: helper calls", calls, helped.size)
        assertEquals("$what: hooks", builds + calls, code.count { it.referenceText() == STORY_MIDCARD })
        for (build in at) {
            val type = when (val call = code[build]) {
                is FiveRegisterInstruction -> call.registerE
                is RegisterRangeInstruction -> call.startRegister + 2
                else -> error("$what: ${call.opcode}")
            }
            assertCheck(what, code, build + 1, type, answer, mapper)
        }
        for (call in helped) {
            val instruction = code[call]
            val operand = instruction.methodReference()!!.parameterTypes.map(CharSequence::toString)
                .takeWhile { it != integer }.sumOf { if (it == "J" || it == "D") 2 else 1 }
            val type = when (instruction) {
                is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG)[operand]
                is RegisterRangeInstruction -> instruction.startRegister + operand
                else -> error("$what: ${instruction.opcode}")
            }
            assertCheck(what, code, call - 7, type, answer, mapper)
        }
    }

    /** The seven instructions from [start]: the namer handed [type], the hook asked, and [answer] on a yes. */
    private fun assertCheck(what: String, code: List<Instruction>, start: Int, type: Int, answer: Opcode, mapper: String) {
        assertEquals("$what: the namer", Opcode.INVOKE_STATIC_RANGE, code[start].opcode)
        assertEquals("$what: the namer", mapper, code[start].referenceText())
        assertEquals("$what: the type", type, (code[start] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the name", Opcode.MOVE_RESULT_OBJECT, code[start + 1].opcode)
        val free = (code[start + 1] as OneRegisterInstruction).registerA
        assertEquals("$what: the hook", STORY_MIDCARD, code[start + 2].referenceText())
        assertEquals("$what: what it's handed", free, (code[start + 2] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[start + 3].opcode)
        assertEquals("$what: the answer's register", free, (code[start + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: the check", Opcode.IF_EQZ, code[start + 4].opcode)
        assertEquals("$what: Instagram's code", 5, (code[start + 4] as OffsetInstruction).codeOffset)
        assertEquals("$what: no card", 0, (code[start + 5] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: answered", answer, code[start + 6].opcode)
        assertEquals("$what: answered", free, (code[start + 6] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 451's: a static namer of midcard types holding [name], a reel constructor asking it
     * with the type it takes, and the adders. One adds the Suggested for you page after asking
     * [repository] and answers whether it did, one answers the card or null, one builds two cards
     * (one with a range call), and a rebuild of reels from their ids takes no list. Two more hand
     * the type to a static helper that builds the card and adds it, shaped like the x86 build's
     * `0C4G.A1I`, one with a range call; with [helperJump] something jumps straight to the first's call.
     */
    private fun classes(
        name: String = SUGGESTED_USERS_MIDCARD,
        twoMappers: Boolean = false,
        reelNames: Boolean = true,
        adders: Boolean = true,
        repository: String = SUGGESTED_USERS_REPOSITORY,
        helperJump: Boolean = false,
    ): List<ClassDef> {
        val namer = method(names, "A00", listOf(integer), "Ljava/lang/String;", 1, static, "const-string v0, \"$name\"\nreturn-object v0")
        val second = method(names, "A01", listOf(integer), "Ljava/lang/String;", 1, static, "const-string v0, \"$name\"\nreturn-object v0")
        val init = method(
            reel, "<init>", listOf("Lfixture/Owner;", integer, list, list), "V", 1, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            (if (reelNames) "invoke-static {p2}, $mapperRef\nmove-result-object v0\n" else "") + "return-void",
        )
        val sync = method(
            "Lfixture/SuggestedUsersAdder;", "A03", listOf(session, list, "I"), "Z", 3, static,
            """
                invoke-static {p0}, $repository->A00($session)Ljava/lang/Object;
                move-result-object v1
                if-nez v1, :build
                const/4 v0, 0x0
                return v0
                :build
                sget-object v0, $types->A0j:$integer
                new-instance v1, $reel
                const/4 v2, 0x0
                invoke-direct {v1, v2, v0, v2, v2}, $reelInit
                invoke-interface {p1, p2, v1}, $list->add(ILjava/lang/Object;)V
                const/4 v0, 0x1
                return v0
            """,
        )
        val async = method(
            "Lfixture/SuggestedUsersAsync;", "A01", listOf(session, list, "I"), "Lfixture/Pair;", 3, static,
            """
                if-nez p2, :build
                const/4 v0, 0x0
                return-object v0
                :build
                sget-object v0, $types->A0j:$integer
                new-instance v1, $reel
                const/4 v2, 0x0
                invoke-direct {v1, v2, v0, v2, v2}, $reelInit
                new-instance v0, Lfixture/Pair;
                return-object v0
            """,
        )
        val two = method(
            "Lfixture/TwoCards;", "A00", listOf(session, list), "Z", 5, static,
            """
                sget-object v2, $types->A0N:$integer
                new-instance v0, $reel
                const/4 v1, 0x0
                const/4 v3, 0x0
                const/4 v4, 0x0
                invoke-direct/range {v0 .. v4}, $reelInit
                invoke-interface {p1, v0}, $list->add(Ljava/lang/Object;)Z
                sget-object v2, $types->A0Y:$integer
                new-instance v0, $reel
                invoke-direct {v0, v1, v2, v3, v4}, $reelInit
                invoke-interface {p1, v0}, $list->add(Ljava/lang/Object;)Z
                const/4 v0, 0x1
                return v0
            """,
        )
        val rebuild = method(
            "Lfixture/Rebuild;", "A17", listOf("Lfixture/Viewer;", "Z"), "V", 3, static,
            """
                sget-object v0, $types->A00:$integer
                new-instance v1, $reel
                const/4 v2, 0x0
                invoke-direct {v1, v2, v0, v2, v2}, $reelInit
                return-void
            """,
        )
        val helper = method(
            "Lfixture/MidcardHelper;", "A1I", listOf(session, integer, list, "I"), "V", 2, static,
            """
                new-instance v0, $reel
                const/4 v1, 0x0
                invoke-direct {v0, v1, p1, v1, v1}, $reelInit
                invoke-interface {p2, p3, v0}, $list->add(ILjava/lang/Object;)V
                return-void
            """,
        )
        val trending = method(
            "Lfixture/Trending;", "A00", listOf(session, list, "I"), "Z", 3, static,
            """
                if-nez p2, :add
                const/4 v0, 0x0
                return v0
                :add
                const/4 v1, 0x1
                sget-object v0, $types->A03:$integer
            """ + (if (helperJump) "if-eqz p2, :call\nsget-object v0, $types->A04:$integer\n:call\n" else "") +
                """
                invoke-static {p0, v0, p1, p2}, $helperRef
                return v1
            """,
        )
        val trendingRange = method(
            "Lfixture/TrendingRange;", "A00", listOf(session, list, "I"), "Lfixture/Pair;", 5, static,
            """
                if-nez p2, :add
                const/4 v0, 0x0
                return-object v0
                :add
                move-object v0, p0
                sget-object v1, $types->A04:$integer
                move-object v2, p1
                move v3, p2
                invoke-static/range {v0 .. v3}, $helperRef
                new-instance v0, Lfixture/Pair;
                return-object v0
            """,
        )
        return listOfNotNull(
            classDef(names, if (twoMappers) listOf(namer, second) else listOf(namer)),
            classDef(reel, listOf(init)),
            if (adders) classDef("Lfixture/SuggestedUsersAdder;", listOf(sync)) else null,
            if (adders) classDef("Lfixture/SuggestedUsersAsync;", listOf(async)) else null,
            if (adders) classDef("Lfixture/TwoCards;", listOf(two)) else null,
            classDef("Lfixture/Rebuild;", listOf(rebuild)),
            classDef("Lfixture/MidcardHelper;", listOf(helper)),
            if (adders) classDef("Lfixture/Trending;", listOf(trending)) else null,
            if (adders) classDef("Lfixture/TrendingRange;", listOf(trendingRange)) else null,
        )
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

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

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
     * on a yes. The code rebuilding reels from their ids, which takes no list, stays as it was.
     */
    @Test
    fun eachAdderAsksRightAfterItsBuild() {
        val classes = classes()
        val context = PatchContexts.of(classes)
        val midcards = context.findStoryMidcards()
        assertEquals(mapperRef, midcards.mapper.toString())
        assertEquals(reelInit, midcards.reel.toString())
        assertEquals(
            listOf("Lfixture/SuggestedUsersAdder;->A03", "Lfixture/SuggestedUsersAsync;->A01", "Lfixture/TwoCards;->A00"),
            midcards.adders.map { "${it.type}->${it.name}" }.sorted(),
        )

        context.hideStoryMidcards(midcards)

        assertHooked("sync", context.mutableClassDefBy("Lfixture/SuggestedUsersAdder;").methods.single(), Opcode.RETURN, builds = 1)
        assertHooked("async", context.mutableClassDefBy("Lfixture/SuggestedUsersAsync;").methods.single(), Opcode.RETURN_OBJECT, builds = 1)
        assertHooked("two builds", context.mutableClassDefBy("Lfixture/TwoCards;").methods.single(), Opcode.RETURN, builds = 2)
        val rebuild = context.mutableClassDefBy("Lfixture/Rebuild;").methods.single()
        assertEquals("the rebuild changed", classes.single { it.type == "Lfixture/Rebuild;" }.methods.single().code().map { it.opcode }, rebuild.code().map { it.opcode })
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
            classes(repository = "Lfixture/SomeOtherRepository;") to "none of the 3 midcard adder(s) asks $SUGGESTED_USERS_REPOSITORY",
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
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val builds = classDef.methods.any { m ->
                        m.code().any { instruction ->
                            instruction.methodReference()?.let {
                                it.definingClass in reels && it.name == "<init>" && it.parameterTypes.drop(1).map(CharSequence::toString) == listOf(integer, list, list)
                            } == true
                        }
                    }
                    if (builds && classDef.type !in holders) holders[classDef.type] = ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders.values)

            val midcards = context.findStoryMidcards()
            context.hideStoryMidcards(midcards)

            assertTrue("${bundle.name}: the namer ${midcards.mapper}", midcards.mapper.definingClass in namers)
            assertTrue("${bundle.name}: the reel ${midcards.reel}", midcards.reel.definingClass in reels)
            val reelText = midcards.reel.toString()
            for (site in midcards.adders) {
                val before = holders.getValue(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
                val after = context.mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }
                val answer = if (after.returnType == "Z") Opcode.RETURN else Opcode.RETURN_OBJECT
                assertHooked("${bundle.name}: ${site.type}->${site.name}", after, answer, before.code().count { it.referenceText() == reelText }, midcards.mapper.toString(), reelText)
            }
            // On 451 that's eight adders, the async suggested users one among them, answering an object.
            val answers = midcards.adders.map { site ->
                holders.getValue(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }.returnType == "Z"
            }
            assertTrue("${bundle.name}: no boolean adder in ${midcards.adders.map { "${it.type}->${it.name}" }}", true in answers)
            assertTrue("${bundle.name}: no adder answering an object in ${midcards.adders.map { "${it.type}->${it.name}" }}", false in answers)
        }
    }

    /**
     * Right after each call to the reel constructor: the namer handed the type the reel took, its
     * answer handed to the hook, and on a yes a zero answered, false or null.
     */
    private fun assertHooked(what: String, method: Method, answer: Opcode, builds: Int, mapper: String = mapperRef, reelText: String = reelInit) {
        val code = method.code()
        val at = code.indices.filter { code[it].referenceText() == reelText }
        assertEquals("$what: builds", builds, at.size)
        assertEquals("$what: hooks", builds, code.count { it.referenceText() == STORY_MIDCARD })
        for (build in at) {
            val type = when (val call = code[build]) {
                is FiveRegisterInstruction -> call.registerE
                is RegisterRangeInstruction -> call.startRegister + 2
                else -> error("$what: ${call.opcode}")
            }
            assertEquals("$what: the namer", Opcode.INVOKE_STATIC_RANGE, code[build + 1].opcode)
            assertEquals("$what: the namer", mapper, code[build + 1].referenceText())
            assertEquals("$what: the type", type, (code[build + 1] as RegisterRangeInstruction).startRegister)
            assertEquals("$what: the name", Opcode.MOVE_RESULT_OBJECT, code[build + 2].opcode)
            val free = (code[build + 2] as OneRegisterInstruction).registerA
            assertEquals("$what: the hook", STORY_MIDCARD, code[build + 3].referenceText())
            assertEquals("$what: what it's handed", free, (code[build + 3] as RegisterRangeInstruction).startRegister)
            assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[build + 4].opcode)
            assertEquals("$what: the answer's register", free, (code[build + 4] as OneRegisterInstruction).registerA)
            assertEquals("$what: the check", Opcode.IF_EQZ, code[build + 5].opcode)
            assertEquals("$what: Instagram's code", 5, (code[build + 5] as OffsetInstruction).codeOffset)
            assertEquals("$what: no card", 0, (code[build + 6] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$what: answered", answer, code[build + 7].opcode)
            assertEquals("$what: answered", free, (code[build + 7] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * Shaped like 451's: a static namer of midcard types holding [name], a reel constructor asking it
     * with the type it takes, and the adders. One adds the Suggested for you page after asking
     * [repository] and answers whether it did, one answers the card or null, one builds two cards
     * (one with a range call), and a rebuild of reels from their ids takes no list.
     */
    private fun classes(
        name: String = SUGGESTED_USERS_MIDCARD,
        twoMappers: Boolean = false,
        reelNames: Boolean = true,
        adders: Boolean = true,
        repository: String = SUGGESTED_USERS_REPOSITORY,
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
        return listOfNotNull(
            classDef(names, if (twoMappers) listOf(namer, second) else listOf(namer)),
            classDef(reel, listOf(init)),
            if (adders) classDef("Lfixture/SuggestedUsersAdder;", listOf(sync)) else null,
            if (adders) classDef("Lfixture/SuggestedUsersAsync;", listOf(async)) else null,
            if (adders) classDef("Lfixture/TwoCards;", listOf(two)) else null,
            classDef("Lfixture/Rebuild;", listOf(rebuild)),
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

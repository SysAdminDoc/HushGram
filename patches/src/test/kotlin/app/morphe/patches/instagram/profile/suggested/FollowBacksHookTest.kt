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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide People you don't follow back, Hide suggested people on profiles' fourth switch: the parser's write and the builder's section, found and answered. */
class FollowBacksHookTest {
    private val parser = "Lfixture/InboxParser;"
    private val json = "Lfixture/Json;"
    private val inbox = "Lfixture/Inbox;"
    private val followBacksField = "$inbox->followBacks:Ljava/util/List;"
    private val builder = "Lfixture/NewsBuilder;"

    /** The hooks the patch writes are in the ProfileSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FOLLOW_BACKS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(FOLLOW_BACKS, SUGGESTION_SECTION)) {
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * In each declared build the notifications' list builder is the one method loading
     * FRIENDING_UNIT_FALLBACK, and the section hook goes right after its rows check for a server
     * section, before FOLLOW_BACK loads and the header goes in: the section's type in, the answer in the
     * rows check's register, and a yes taking the rows check's own way past the section. On 450's
     * 385611438 that's LX/0BYR.A00, the type in v15 and the answer in v2.
     */
    @Test
    fun eachDeclaredBuildSkipsTheFollowBackSection() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == FALLBACK_SECTION } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val site = context.findFollowBackSection()
            context.hideFollowBackSection(site)

            val patched = context.mutableClassDefBy(site.type).methods.single {
                it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(CharSequence::toString) == site.parameters
            }
            val code = patched.code()
            assertEquals("${bundle.name}: hooks", 1, code.count { it.referenceText() == SUGGESTION_SECTION })
            val call = code.indexOfFirst { it.referenceText() == SUGGESTION_SECTION }
            assertEquals("${bundle.name}: the call", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
            assertEquals("${bundle.name}: what it's handed", site.section, (code[call] as RegisterRangeInstruction).startRegister)
            assertEquals("${bundle.name}: one register", 1, (code[call] as RegisterRangeInstruction).registerCount)
            assertEquals("${bundle.name}: the answer", Opcode.MOVE_RESULT, code[call + 1].opcode)
            assertEquals("${bundle.name}: the answer's register", site.answer, (code[call + 1] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: the yes branch", Opcode.IF_NEZ, code[call + 2].opcode)
            assertEquals("${bundle.name}: what it tests", site.answer, (code[call + 2] as OneRegisterInstruction).registerA)
            assertEquals("${bundle.name}: FOLLOW_BACK loads next", FOLLOW_BACK_TYPE, code[call + 3].string())
            assertEquals("${bundle.name}: the rows check comes first", "Ljava/util/List;->isEmpty()Z", code[call - 3].referenceText())
            assertEquals("${bundle.name}: the rows check's branch", Opcode.IF_NEZ, code[call - 1].opcode)
            assertEquals("${bundle.name}: a yes goes where no rows go", patched.targetOf(call - 1), patched.targetOf(call + 2))
            assertTrue("${bundle.name}: the way past the section is before it", patched.targetOf(call + 2) < call)
            val targets = patched.jumpTargets()
            assertFalse("${bundle.name}: a jump lands in or skips the call", call in targets || call + 1 in targets || call + 2 in targets)
            val hash = (call + 3 until code.size).first { code[it].referenceText() == "Ljava/lang/String;->hashCode()I" }
            val switched = (code[hash] as? RegisterRangeInstruction)?.startRegister ?: (code[hash] as FiveRegisterInstruction).registerC
            assertEquals("${bundle.name}: the type the header switch reads", site.section, switched)
        }
    }

    /**
     * The list goes through the extension right before the parser keeps it, the call sitting where the
     * loop over the accounts lands, and the other key's list is left alone.
     */
    @Test
    fun theParsersListIsAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().single().methods.single().code().size

        val site = context.findFollowBacks()
        context.hideFollowBacks(site)

        assertEquals("the field", followBacksField, site.field)
        val patched = context.mutableClassDefBy(parser).methods.single()
        assertAnswered("stand-in", patched, site.register, site.field)
        assertEquals("what else was added", before + 2, patched.code().size)
        val code = patched.code()
        val hook = code.indexOfFirst { it.referenceText() == FOLLOW_BACKS }
        val outOfAccounts = code.indexOfFirst { it.referenceText() == "$json->user()Ljava/lang/Object;" } + 2
        assertEquals("the loop's test", Opcode.IF_EQZ, code[outOfAccounts].opcode)
        assertEquals("the loop's way out lands on the call", hook, patched.targetOf(outOfAccounts))
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(keys = 0) to "expected one parser loading \"$FOLLOW_BACKS_KEY\" once, found 0 loads",
            classes(keys = 2) to "expected one parser loading \"$FOLLOW_BACKS_KEY\" once, found 2 loads",
            classes(madeOf = "Ljava/util/LinkedList;") to "$parser->unsafeParseFromJson doesn't make a list for \"$FOLLOW_BACKS_KEY\"",
            classes(fieldType = "Ljava/util/Collection;") to "expected one write of the \"$FOLLOW_BACKS_KEY\" list in $parser->unsafeParseFromJson, found 0",
            classes(readAfter = true) to "$parser->unsafeParseFromJson reads v3 after keeping \"$FOLLOW_BACKS_KEY\"",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideFollowBacks(context.findFollowBacks())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == FOLLOW_BACKS } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the one load of the key is the notifications' parser's, and its list is
     * answered right before the write, where the loop over the accounts lands. On 450's 385611438
     * that's LX/081e.unsafeParseFromJson writing LX/028N.A0Q.
     */
    @Test
    fun eachDeclaredBuildHidesTheSection() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { m -> m.code().any { it.string() == FOLLOW_BACKS_KEY } }) holders += ImmutableClassDef.of(classDef)
                }
            }
            val context = PatchContexts.of(holders)

            val site = context.findFollowBacks()
            context.hideFollowBacks(site)

            val patched = context.mutableClassDefBy(site.type).methods.single {
                it.name == "unsafeParseFromJson" && it.returnType == "Ljava/lang/Object;" &&
                    it.parameterTypes.map(CharSequence::toString) == site.parameters
            }
            assertAnswered(bundle.name, patched, site.register, site.field)
            val hook = patched.code().indexOfFirst { it.referenceText() == FOLLOW_BACKS }
            val targets = patched.jumpTargets()
            assertTrue("${bundle.name}: the loop over the accounts lands on the call", hook in targets)
            assertFalse("${bundle.name}: a jump skips the call", hook + 1 in targets || hook + 2 in targets)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == FOLLOW_BACKS } } },
            )
        }
    }

    /**
     * The section hook reads the type's register at FOLLOW_BACK's load, so it has to hold a String on
     * every path there, branches included. A build where the load can see something else is refused,
     * even with no write on the straight line from the load to hashCode, and the patch goes on without
     * the hook, writing nothing. The same shape with the String copied in before the rows check, and a
     * branch between the load and hashCode as 450 has, is hooked.
     */
    @Test
    fun aSectionTypeThatMightNotBeAStringIsRefused() {
        val good = PatchContexts.of(builderClasses(retyped = false))
        val site = good.findFollowBackSection()
        good.hideFollowBackSection(site)
        assertEquals("the type's register", 15, site.section)
        assertEquals("hooked", 1, good.mutableClassDefBy(builder).methods.single().code().count { it.referenceText() == SUGGESTION_SECTION })

        val bad = PatchContexts.of(builderClasses(retyped = true))
        val failure = assertThrows(PatchException::class.java) { bad.findFollowBackSection() }
        assertTrue(failure.message, failure.message!!.contains("v15 doesn't hold a String on every path"))
        assertNull("the patch goes on without the hook", bad.findFollowBackSectionOrWarn())
        assertTrue("nothing written", bad.mutableClassDefBy(builder).methods.single().code().none { it.referenceText() == SUGGESTION_SECTION })
    }

    /** The range call with the list's register, its answer back in that register, then the parser's own write of it. */
    private fun assertAnswered(what: String, method: Method, register: Int, field: String) {
        val code = method.code()
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == FOLLOW_BACKS })
        val call = code.indexOfFirst { it.referenceText() == FOLLOW_BACKS }
        assertTrue("$what: the key loads before the call", (0 until call).any { code[it].string() == FOLLOW_BACKS_KEY })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[call].opcode)
        assertEquals("$what: what it's handed", register, (code[call] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: one register", 1, (code[call] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        assertEquals("$what: the register", register, (code[call + 1] as OneRegisterInstruction).registerA)
        val write = code[call + 2]
        assertEquals("$what: the write", Opcode.IPUT_OBJECT, write.opcode)
        assertEquals("$what: what's written", register, (write as TwoRegisterInstruction).registerA)
        assertEquals("$what: where", field, ((write as ReferenceInstruction).reference as FieldReference).let { "${it.definingClass}->${it.name}:${it.type}" })
    }

    /**
     * Shaped like 450's: the parser loops over the reply's keys, makes a new list for each list key,
     * fills the follow back one in a loop that branches to the write when it runs out, writes each
     * list into a field of its model, and returns the model when the keys run out.
     */
    private fun classes(
        keys: Int = 1,
        madeOf: String = "Ljava/util/ArrayList;",
        fieldType: String = "Ljava/util/List;",
        readAfter: Boolean = false,
    ): List<ClassDef> {
        val followBacks = List(keys) { n ->
            """
                :follow$n
                const-string v0, "$FOLLOW_BACKS_KEY"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :${if (n + 1 < keys) "follow${n + 1}" else "next"}
                new-instance v3, $madeOf
                invoke-direct {v3}, $madeOf-><init>()V
                :item$n
                invoke-virtual {p1}, $json->user()Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :kept$n
                invoke-virtual {v3, v0}, Ljava/util/AbstractCollection;->add(Ljava/lang/Object;)Z
                goto :item$n
                :kept$n
                iput-object v3, v6, $inbox->followBacks${if (n == 0) "" else "$n"}:$fieldType
                goto :next
            """.trimIndent()
        }.joinToString("\n")
        val body = listOfNotNull(
            "new-instance v6, $inbox",
            "invoke-direct {v6}, $inbox-><init>()V",
            ":next",
            "invoke-virtual {p1}, $json->name()Ljava/lang/String;",
            "move-result-object v1",
            "if-eqz v1, :done",
            "const-string v0, \"friend_request_stories\"",
            "invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "move-result v0",
            "if-eqz v0, :${if (keys > 0) "follow0" else "next"}",
            "new-instance v3, Ljava/util/ArrayList;",
            "invoke-direct {v3}, Ljava/util/ArrayList;-><init>()V",
            "iput-object v3, v6, $inbox->requests:Ljava/util/List;",
            "goto :next",
            followBacks.ifEmpty { null },
            ":done",
            if (readAfter) "invoke-static {v3}, Lfixture/Log;->kept(Ljava/lang/Object;)V" else null,
            "const-string v0, \"old_stories\"",
            "return-object v6",
        ).joinToString("\n")
        val parse = method(parser, "unsafeParseFromJson", listOf(json), "Ljava/lang/Object;", 7, AccessFlags.PUBLIC.value, body)
        return listOf(classDef(parser, "Ljava/lang/Object;", listOf(parse)))
    }

    /**
     * Shaped like 450's builder around FOLLOW_BACK: the section's type copied from a String parameter,
     * its rows checked, FOLLOW_BACK loaded, a branch, then the header's switch on the type. [retyped]
     * puts an int in the type's register instead and writes the String only on a path the load jumps
     * to, so hashCode still reads a String while the load sees the int.
     */
    private fun builderClasses(retyped: Boolean): List<ClassDef> {
        val body = listOfNotNull(
            "const-string v0, \"$FALLBACK_SECTION\"",
            if (retyped) "const/16 v15, 0x7" else "move-object/from16 v15, p0",
            "move-object/from16 v8, p1",
            "invoke-interface {v8}, Ljava/util/List;->isEmpty()Z",
            "move-result v2",
            "if-nez v2, :done",
            "const-string v11, \"$FOLLOW_BACK_TYPE\"",
            if (retyped) "goto :typed" else "if-eqz v8, :header",
            ":header",
            "invoke-virtual {v15}, Ljava/lang/String;->hashCode()I",
            "move-result v4",
            "const v2, ${FOLLOW_BACK_TYPE.hashCode()}",
            "if-ne v4, v2, :done",
            "invoke-virtual {v15, v11}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            ":done",
            "return-void",
            if (retyped) ":typed" else null,
            if (retyped) "move-object/from16 v15, p0" else null,
            if (retyped) "goto :header" else null,
        ).joinToString("\n")
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val build = method(builder, "build", listOf("Ljava/lang/String;", "Ljava/util/List;"), "V", 16, flags, body)
        return listOf(classDef(builder, "Ljava/lang/Object;", listOf(build)))
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

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun MutableMethod.targetOf(index: Int): Int =
        (implementation!!.instructions[index] as BuilderOffsetInstruction).target.location.index

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

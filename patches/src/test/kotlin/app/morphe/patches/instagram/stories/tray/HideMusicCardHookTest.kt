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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hide the Music for you card's half of Hide suggested stories: the card's flag read, found and answered. */
class HideMusicCardHookTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val gate = "Lfixture/MusicCardGate;"
    private val caller = "Lfixture/MusicCardInjector;"
    private val repository = "Lfixture/MusicCardRepository;"
    private val flagHex = MUSIC_CARD_FLAG.toString(16)

    /** The hook the patch writes is in the StoriesTray the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(MUSIC_CARD.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MUSIC_CARD is not in the extension: $declared", MUSIC_CARD.substringAfter("->") in declared)
    }

    /** The check's flag read gets its answer passed through the extension, and nothing else in it changes. */
    @Test
    fun theCardsFlagIsAnswered() {
        val context = PatchContexts.of(classes())
        val before = classes().single { it.type == gate }.methods.single { it.name == "A00" }.code().size

        context.hideMusicCard()

        val patched = context.mutableClassDefBy(gate).methods.single { it.name == "A00" }
        assertAnswered("stand-in", patched)
        assertEquals("what else was added", before + 2, patched.code().size)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(repositoryHolds = "SomethingElse") to "no class holds $MUSIC_CARD_REPOSITORY",
            classes(reads = 0) to "expected one read of the Music for you card flag $flagHex, found 0",
            classes(reads = 2) to "expected one read of the Music for you card flag $flagHex, found 2",
            classes(flag = MUSIC_CARD_FLAG + 1) to "expected one read of the Music for you card flag $flagHex, found 0",
            classes(sharedRead = true) to "the read of $flagHex in $gate->A00 is shared with another flag",
            classes(takes = "Ljava/lang/Object;") to "$flagHex is read in $gate->A00(Ljava/lang/Object;)Z, not in a check of the session",
            classes(casts = false) to "nothing that asks $gate->A00 casts to the class holding $MUSIC_CARD_REPOSITORY",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hideMusicCard() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == MUSIC_CARD } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the flag's one read is a check of the session, asked by code that
     * casts to the music midcard repository, and it's answered. On 450 that's LX/05kB.A00, asked
     * by the code that adds the card to the viewer's list.
     */
    @Test
    fun eachDeclaredBuildHidesTheCard() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("the seven 450 builds: ${bundles.map { it.name }}", 7, bundles.size)
        for (bundle in bundles) {
            val loaders = mutableMapOf<String, String>()
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val loads = classDef.methods.filter { it.loadsTheFlag() }
                    if (loads.isNotEmpty()) loaders[classDef.type] = loads.joinToString { it.name }
                    if (loads.isNotEmpty() || classDef.methods.any { it.holds(MUSIC_CARD_REPOSITORY) }) holders += ImmutableClassDef.of(classDef)
                }
            }
            assertEquals("${bundle.name}: classes loading $flagHex: $loaders", 1, loaders.size)
            val gateType = loaders.keys.single()
            val gateName = loaders.values.single()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.type != gateType && classDef.methods.any { m -> m.code().any { it.calls(gateType, gateName) } }) {
                        holders += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val context = PatchContexts.of(holders)

            val read = context.findMusicCard()
            context.hideMusicCard()

            assertEquals("${bundle.name}: the check", "$gateType->$gateName", "${read.type}->${read.name}")
            val patched = context.mutableClassDefBy(read.type).methods
            assertAnswered(bundle.name, patched.single { it.name == read.name && it.parameterTypes.map(CharSequence::toString) == listOf(session) })
            assertEquals("${bundle.name}: methods hooked", 1, patched.count { m -> m.code().any { it.referenceText() == MUSIC_CARD } })
        }
    }

    /** Right after the flag's move-result: the range call with that register, and its answer back in it. */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == MUSIC_CARD_FLAG }
        assertTrue("$what: no load of $flagHex", load >= 0)
        val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == MUSIC_CARD })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", MUSIC_CARD, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    /**
     * Shaped like 450's: a static check of the session reading the flag through a cast config, a
     * caller that casts something to the repository class and asks the check, and the repository,
     * whose constructor holds its name.
     */
    private fun classes(
        repositoryHolds: String = MUSIC_CARD_REPOSITORY,
        reads: Int = 1,
        flag: Long = MUSIC_CARD_FLAG,
        sharedRead: Boolean = false,
        takes: String = session,
        casts: Boolean = true,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
        val readFlag = List(reads) {
            """
                invoke-static {p0}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                ${if (sharedRead) "if-eqz v2, :cast$it" else ""}
                const-wide v0, 0x${flag.toString(16)}L
                ${if (sharedRead) ":cast$it" else ""}
                check-cast v2, $configs
                invoke-interface {v2, v0, v1}, $configs->read(J)Z
                move-result v0
            """.trimIndent()
        }.joinToString("\n")
        val check = method(gate, "A00", listOf(takes), "Z", 3, static, readFlag + "\nreturn v0")
        val ask = method(
            caller, "A00", listOf(session, "Ljava/lang/Object;"), "Z", 2, static,
            listOf(
                if (casts) "check-cast p1, $repository" else "",
                "invoke-static {p0}, $gate->A00($takes)Z",
                "move-result v0",
                "return v0",
            ).filter { it.isNotBlank() }.joinToString("\n"),
        )
        val init = method(
            repository, "<init>", listOf(session), "V", 2, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            "const-string v0, \"$repositoryHolds\"\nreturn-void",
        )
        return listOf(
            classDef(gate, "Ljava/lang/Object;", listOf(check)),
            classDef(caller, "Ljava/lang/Object;", listOf(ask)),
            classDef(repository, "Ljava/lang/Object;", listOf(init)),
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

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null, methods)

    private fun Method.loadsTheFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == MUSIC_CARD_FLAG
    }

    private fun Method.holds(string: String): Boolean = code().any { it.string() == string }

    private fun Instruction.calls(type: String, name: String): Boolean {
        val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return called.definingClass == type && called.name == name
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}

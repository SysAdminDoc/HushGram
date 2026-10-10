/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideInboxSuggestionsHookTest {
    private val loader = "Lfixture/InboxLoader;"
    private val sections = "Lfixture/InboxSections;"
    private val unitsSet = listOf("Ljava/util/List;", "Ljava/lang/String;")

    /** The hook the patch writes is in the InboxSuggestions the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(INBOX_UNITS.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$INBOX_UNITS is not in the extension: $declared", INBOX_UNITS.substringAfter("->") in declared)
    }

    /**
     * The units go through the question first thing in the method building the section, whether the
     * loader calls it plainly or by range, and the rest of the method is Instagram's own.
     */
    @Test
    fun theUnitsAreAskedForFirstThing() {
        for (range in listOf(false, true)) {
            val classes = classes(range = range)
            val context = PatchContexts.of(classes)
            val stock = classes.single { it.type == sections }.methods.single { it.name == "build" }

            context.hide()

            val build = context.mutableClassDefBy(sections).methods.single { it.name == "build" }
            assertUnitsAsked("range $range", build)
            assertEquals("range $range: the stock code follows", stock.code().map { it.describe() }, build.code().drop(2).map { it.describe() })
            val load = context.mutableClassDefBy(loader).methods.single { it.name == "load" }
            assertTrue("range $range: the loader isn't touched", load.code().none { it.referenceText() == INBOX_UNITS })
        }
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(loaders = 0) to "expected one method holding $PREFETCHED, found 0",
            classes(loaders = 2) to "expected one method holding $PREFETCHED, found 2",
            classes(setters = 0) to "to hand its units to one method, found 0",
            classes(setters = 2) to "to hand its units to one method, found 2",
            classes(sectionsInBuild = false) to "$sections isn't in this build",
            classes(readsRequests = false) to "$sections doesn't read $FOLLOW_REQUESTS",
            classes(declared = false) to "isn't declared in $sections",
            classes(static = true) to "isn't an instance method with code",
            classes(code = false) to "isn't an instance method with code",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hide() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    val after = now.getValue(method.key())
                    assertEquals("$expected: ${original.type}->${method.name} changed", method.code().map { it.describe() }, after.code().map { it.describe() })
                }
            }
        }
    }

    /** In each declared build the method building the section is found and asks for its units, once, and the stock code is kept. */
    @Test
    fun eachDeclaredBuildAsksForTheUnits() {
        forEachFixture { bundle ->
            val classes = listOf(PREFETCHED, FOLLOW_REQUESTS).flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
            val context = PatchContexts.of(classes)
            val site = context.findInboxUnitsSet()
            val stock = context.mutableClassDefBy(site.type).methods.single {
                it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
            }
            val original = NeutralNativePath(stock)

            context.hide()

            val written = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                .filter { method -> method.code().any { it.referenceText() == INBOX_UNITS } }
            assertEquals("${bundle.name}: methods asking", 1, written.size)
            val build = written.single()
            assertEquals("${bundle.name}: the method asking", "${site.type}->${site.name}", "${build.definingClass}->${build.name}")
            assertUnitsAsked(bundle.name, build)
            original.assertPreserved(bundle.name, build, setOf(0, 1))
        }
    }

    private fun forEachFixture(check: (java.io.File) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The question on the units' register, p1, as the first instruction, and its answer back into that register. */
    private fun assertUnitsAsked(what: String, build: Method) {
        val code = build.code()
        val units = build.implementation!!.registerCount - 2
        assertEquals("$what: asks", 1, code.count { it.referenceText() == INBOX_UNITS })
        assertEquals("$what: asks first", INBOX_UNITS, code[0].referenceText())
        assertEquals("$what: hands over the units", listOf(units), code[0].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: the answer's register", units, (code[1] as OneRegisterInstruction).registerA)
    }

    private fun BytecodePatchContext.hide() = hookInboxUnits(findInboxUnitsSet())

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The inbox's loader, which logs [PREFETCHED] and hands the units it fetched to the method
     * building the section, and the class of that method, which reads follow requests by name.
     */
    private fun classes(
        loaders: Int = 1,
        setters: Int = 1,
        range: Boolean = false,
        sectionsInBuild: Boolean = true,
        readsRequests: Boolean = true,
        declared: Boolean = true,
        static: Boolean = false,
        code: Boolean = true,
    ): List<ClassDef> {
        val call = { name: String ->
            if (range) "invoke-virtual/range { v0 .. v2 }, $sections->$name(Ljava/util/List;Ljava/lang/String;)V"
            else "invoke-virtual { v0, v1, v2 }, $sections->$name(Ljava/util/List;Ljava/lang/String;)V"
        }
        val calls = listOf("build", "rebuild").take(setters).joinToString("\n") { call(it) }
        val loads = (0 until 2).map { copy ->
            val logs = if (copy < loaders) "const-string v0, \"$PREFETCHED\"" else "const-string v0, \"recommended_users_loaded\""
            method(loader, if (copy == 0) "load" else "reload", listOf("Z"), "V", 3, body = """
                $logs
                const/4 v0, 0x0
                const/4 v1, 0x0
                const-string v2, "inbox"
                ${if (copy == 0) calls else ""}
                return-void
            """)
        }
        val loaderClass = classDef(loader, loads)

        val built = if (declared) "build" else "other"
        val buildMethods = listOf(built, "rebuild").map { name ->
            if (!code) {
                ImmutableMethod(
                    sections, name, unitsSet.map { ImmutableMethodParameter(it, null, null) }, "V",
                    AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
                )
            } else {
                method(sections, name, unitsSet, "V", 2, static = static, body = """
                    if-eqz p1, :none
                    const/4 v0, 0x0
                    invoke-interface { p1, v0 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                    move-result-object v1
                    :none
                    return-void
                """)
            }
        }
        val requests = method(sections, "requests", listOf("Ljava/util/List;"), "V", 1, body = """
            const-string v0, "${if (readsRequests) FOLLOW_REQUESTS else "message_suggestions"}"
            return-void
        """)
        val sectionsClass = classDef(sections, buildMethods + requests)
        return if (sectionsInBuild) listOf(loaderClass, sectionsClass) else listOf(loaderClass)
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.referenceText(): String? = when (val reference = (this as? ReferenceInstruction)?.reference) {
        is StringReference -> reference.string
        null -> null
        else -> reference.toString()
    }

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers, literal and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        if (this@describe is NarrowLiteralInstruction) append(" #$narrowLiteral")
        referenceText()?.let { append(" $it") }
    }
}

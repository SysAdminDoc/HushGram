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

/** Hide Suggested for you between stories, a switch of Hide suggested stories: the viewer's netego check, found and guarded. */
class HideStorySuggestionsHookTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val mapperType = "Lfixture/NetegoTypes;"
    private val items = "Lfixture/NetegoItems;"
    private val store = "Lfixture/ReelStore;"
    private val getter = "$TRAY_ITEM_INTF->netegoType()Ljava/lang/String;"
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    /** The hook the patch writes is in the StoriesTray the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_NETEGO.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$STORY_NETEGO is not in the extension: $declared", STORY_NETEGO.substringAfter("->") in declared)
    }

    /** The check asks the extension first, with the netego type its reader reads, and Instagram's own check follows unchanged. */
    @Test
    fun theCheckIsGuarded() {
        val context = PatchContexts.of(classes())
        val before = classes().single { it.type == items }.methods.single { it.name == "A07" }.code()

        val check = context.findStoryNetegoCheck()
        context.hideStorySuggestions(check)

        assertEquals("the check", "$items->A07", "${check.type}->${check.name}")
        assertEquals("the getter", getter, check.typeGetter)
        val patched = context.mutableClassDefBy(items).methods.single { it.name == "A07" }
        assertGuarded("stand-in", patched)
        assertEquals("Instagram's check after the guard", before.map { it.opcode }, patched.code().drop(7).map { it.opcode })
        val elsewhere = listOf(mapperType, items, store).flatMap { context.mutableClassDefBy(it).methods }
            .filter { m -> m.name != "A07" && m.code().any { it.referenceText() == STORY_NETEGO } }
        assertTrue("hooked elsewhere: $elsewhere", elsewhere.isEmpty())
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(mapperHolds = "Something else") to "expected one method holding \"$UNSUPPORTED_NETEGO\", found 0",
            classes(namesSuggestedUsers = false) to "the class of $mapperType->A00 doesn't name $SUGGESTED_USERS_NETEGO",
            classes(readers = 2) to "expected one method asking $mapperType->A00, found 2",
            classes(checkTakes = "Ljava/lang/Object;") to "expected one check of a story item asking $items->A02, found 0",
            classes(askerHolds = "Something else") to "nothing asking $items->A07 holds \"$INVALID_NETEGO\"",
            classes(loopsToStart = true) to "something in $items->A07 jumps back to its start",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.hideStorySuggestions(context.findStoryNetegoCheck())
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == STORY_NETEGO } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each of the three 452 builds the chain (the mapper, its reader and the check, asked from
     * two places) is there once and the check is guarded.
     */
    @Test
    fun eachDeclaredBuildGuardsTheCheck() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every 452 build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        val strings = setOf(UNSUPPORTED_NETEGO, SUGGESTED_USERS_NETEGO, INVALID_NETEGO)
        for (bundle in bundles) {
            val holders = mutableListOf<ClassDef>()
            val mappers = mutableSetOf<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val held = classDef.methods.flatMap { m -> m.code().mapNotNull { it.string() } }.filter { it in strings }
                    if (held.isNotEmpty()) holders += ImmutableClassDef.of(classDef)
                    if (UNSUPPORTED_NETEGO in held) mappers += classDef.type
                }
            }
            assertEquals("${bundle.name}: classes holding \"$UNSUPPORTED_NETEGO\"", 1, mappers.size)
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (holders.none { it.type == classDef.type } &&
                        classDef.methods.any { m -> m.code().any { (it.reference() as? MethodReference)?.definingClass?.let { type -> type in mappers } == true } }
                    ) {
                        holders += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val context = PatchContexts.of(holders)

            val check = context.findStoryNetegoCheck()
            context.hideStorySuggestions(check)

            assertTrue("${bundle.name}: the getter ${check.typeGetter}", check.typeGetter.startsWith("$TRAY_ITEM_INTF->") && check.typeGetter.endsWith("()Ljava/lang/String;"))
            val patched = context.mutableClassDefBy(check.type).methods.single {
                it.name == check.name && it.parameterTypes.map(CharSequence::toString) == listOf(session, TRAY_ITEM_INTF)
            }
            assertGuarded(bundle.name, patched, check.typeGetter)
            assertEquals(
                "${bundle.name}: methods hooked", 1,
                holders.map { it.type }.distinct().sumOf { type -> context.mutableClassDefBy(type).methods.count { m -> m.code().any { it.referenceText() == STORY_NETEGO } } },
            )
        }
    }

    /** First thing: the item's netego type into v0, the hook, and a no for the check when it says so. */
    private fun assertGuarded(what: String, method: Method, typeGetter: String = getter) {
        val code = method.code()
        val item = method.implementation!!.registerCount - 1
        assertEquals("$what: the getter", Opcode.INVOKE_INTERFACE_RANGE, code[0].opcode)
        assertEquals("$what: the getter", typeGetter, code[0].referenceText())
        assertEquals("$what: the item", item, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the type", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$what: the hook", STORY_NETEGO, code[2].referenceText())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[3].opcode)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[4].opcode)
        assertEquals("$what: no", Opcode.CONST_4, code[5].opcode)
        assertEquals("$what: the return", Opcode.RETURN, code[6].opcode)
        listOf(1, 3, 5).forEach { assertEquals("$what: v0 at $it", 0, (code[it] as OneRegisterInstruction).registerA) }
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == STORY_NETEGO })
    }

    /**
     * Shaped like 450's: the mapper throwing for an unknown type, beside the reverse map naming the
     * suggested users type; its reader, reading the type off the item; the check asking the reader;
     * and the reel store asking the check, holding the invalid item message.
     */
    private fun classes(
        mapperHolds: String = UNSUPPORTED_NETEGO,
        namesSuggestedUsers: Boolean = true,
        readers: Int = 1,
        checkTakes: String = TRAY_ITEM_INTF,
        askerHolds: String = INVALID_NETEGO,
        loopsToStart: Boolean = false,
    ): List<ClassDef> {
        val mapper = method(
            mapperType, "A00", listOf("Ljava/lang/String;"), "Ljava/lang/Integer;", 2, static,
            """
                const-string v0, "story_survey"
                invoke-virtual {p0, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :unknown
                const/4 v0, 0x1
                invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v0
                return-object v0
                :unknown
                const-string v0, "$mapperHolds"
                new-instance v1, Ljava/lang/UnsupportedOperationException;
                invoke-direct {v1, v0}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V
                throw v1
            """,
        )
        val names = method(
            mapperType, "A01", listOf("Ljava/lang/Integer;"), "Ljava/lang/String;", 1, static,
            "const-string v0, \"${if (namesSuggestedUsers) SUGGESTED_USERS_NETEGO else "story_survey"}\"\nreturn-object v0",
        )
        val readerBody = """
            invoke-interface {p0}, $getter
            move-result-object v0
            if-eqz v0, :none
            invoke-static {v0}, $mapperType->A00(Ljava/lang/String;)Ljava/lang/Integer;
            move-result-object v0
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """
        val reader = method(items, "A02", listOf(TRAY_ITEM_INTF), "Ljava/lang/Integer;", 1, static, readerBody)
        val secondReader = method(items, "A03", listOf(TRAY_ITEM_INTF), "Ljava/lang/Integer;", 1, static, readerBody)
        val check = method(
            items, "A07", listOf(session, checkTakes), "Z", 2, static,
            listOfNotNull(
                if (loopsToStart) ":start" else null,
                "const/4 v0, 0x0",
                "invoke-static {p1}, $items->A02($TRAY_ITEM_INTF)Ljava/lang/Integer;",
                "move-result-object v1",
                if (loopsToStart) "if-eqz v1, :start" else "if-eqz v1, :no",
                "const/4 v0, 0x1",
                if (loopsToStart) null else ":no",
                "return v0",
            ).joinToString("\n"),
        )
        val asker = method(
            store, "A0X", listOf("Ljava/util/Collection;"), "Ljava/util/List;", 3, AccessFlags.PUBLIC.value,
            """
                const-string v0, "$askerHolds"
                const/4 v1, 0x0
                const/4 v2, 0x0
                invoke-static {v1, v2}, $items->A07($session$checkTakes)Z
                return-object v2
            """,
        )
        return listOf(
            classDef(mapperType, listOf(mapper, names)),
            classDef(items, listOfNotNull(reader, if (readers > 1) secondReader else null, check)),
            classDef(store, listOf(asker)),
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

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = (reference() as? MethodReference)?.toString()

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}

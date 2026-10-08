/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import flag names' hook on Instagram's MetaConfig list builder, on stand-ins and on the fixture builds. */
class FlagNameListTest {
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value
    private val entry = "Lfixture/Entry;"
    private val builder = "Lfixture/Builder;"
    private val loader = "Lfixture/Loader;"
    private val string = "Ljava/lang/String;"
    private val fields = listOf("configName", "name", "index", "encodedConfig", "encodedIndex", "bits", "kind", "packageId", "config", "one", "two", "three")

    /**
     * Both of the builder's rows go through the hooks, the parameter label then the config label,
     * on the entry's own register, in front of the new-instance. The readers read the entry's
     * config number and index.
     */
    @Test fun eachRowsLabelsGoThroughTheHooks() {
        val patch = PatchContexts.of(classes())

        val list = patch.findFlagNameList()
        assertEquals(listOf(6, 6), list.rows.map { it.entry })
        assertEquals(listOf(4, 4), list.rows.map { it.parameter })
        assertEquals(listOf(1, 1), list.rows.map { it.config })
        patch.applyFlagNameList(list)

        assertHooked(patch.method(builder, "rows"), 2)
        for ((stub, field) in listOf("getFlagConfigNative" to "config", "getFlagIndexNative" to "index")) {
            val code = patch.method(OVERRIDE_BRIDGE, stub).implementation!!.instructions.toList()
            assertEquals(stub, listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.IGET, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN),
                code.map { it.opcode })
            assertEquals(stub, entry, code[0].reference().toString())
            assertEquals(stub, "$entry->$field:I", code[3].reference().toString())
        }
    }

    /** Anything that makes a row's labels or entry uncertain refuses the lot, before anything changes. */
    @Test fun aRowThatDoesntTraceRefusesBeforeAnythingChanges() {
        val cases = mapOf(
            "a jump into a row" to classes(jumpIn = true),
            "a jump to the new-instance" to classes(jumpToNew = true),
            "an overwritten entry" to classes(overwriteEntry = true),
            "labels from two entries" to classes(twoEntries = true),
            "a label set after its read" to classes(labelAfterRead = true),
            "a path around the config name" to classes(skipConfigRead = true),
            "two builders" to classes() + builderClass("Lfixture/OtherBuilder;"),
            "no schema getter" to classes().filter { it.type != loader },
            "no hooks" to classes().filter { it.type != FLAG_NAMES },
            "no readers" to classes().map { if (it.type == OVERRIDE_BRIDGE) clazz(OVERRIDE_BRIDGE) else it },
        )
        for ((case, classes) in cases) {
            val patch = PatchContexts.of(classes)
            val before = patch.snapshot()

            val failure = runCatching { patch.findFlagNameList() }.exceptionOrNull()

            assertTrue("$case: $failure", failure?.message?.startsWith("Open developer options: ") == true)
            assertEquals(case, before, patch.snapshot())
        }
    }

    /** A list that moved leaves Import flag names out, with one warning, and nothing changed. */
    @Test fun aListThatMovedIsLeftOutWithAWarning() {
        val patch = PatchContexts.of(classes(jumpToNew = true))
        val before = patch.snapshot()

        val warnings = PatchLogCapture.warnings { assertNull(patch.flagNamesOrWarn()) }

        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().startsWith("Open developer options: something jumps to a MetaConfig row's new-instance. "))
        assertTrue(warnings.single(), warnings.single().endsWith(" without Import flag names."))
        assertEquals(before, patch.snapshot())
    }

    /** In each declared build, the one list builder's two rows trace, and the hooks and readers go in. */
    @Test fun eachDeclaredBuildHooksMetaConfigsList() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val rows = hooksTheList(bundle, bundle.name)
                // 450's builder makes its two kinds of row from v6's entry, labels in v4 and v1.
                assertEquals(bundle.name, listOf(46, 72), rows.map { it.at })
                assertEquals(bundle.name, listOf(Triple(6, 4, 1), Triple(6, 4, 1)), rows.map { Triple(it.entry, it.parameter, it.config) })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /**
     * The other builds of a declared version (#77, #95). 385611404 and 385611431 build MetaConfig's
     * list the way 385611438 does and take the hooks. 385611395, 385611400 and the x86 and x86_64
     * builds (385611439, 385611440) make each label in a separate method, so there the finder
     * refuses before anything changes, and Import flag names is left out of them.
     */
    @Test fun everyOtherBuildHooksMetaConfigsListOrLeavesItAlone() {
        val hooked = mutableSetOf<String>()
        val elsewhere = listOf("-385611395", "-385611400", "-385611439", "-385611440")
        for (bundle in Fixtures.otherBuilds()) {
            val label = bundle.parentFile.name
            if (elsewhere.any { label.endsWith(it) }) {
                val patch = PatchContexts.of(slice(bundle))
                val failure = runCatching { patch.findFlagNameList() }.exceptionOrNull()
                assertTrue("$label: $failure", failure?.message?.startsWith("Open developer options: ") == true)
            } else {
                hooksTheList(bundle, label)
                hooked += label
            }
        }
        assertTrue("no other build took the hooks", hooked.isNotEmpty())
    }

    /** Finds and puts in the hook on [bundle]'s slice, checks what went in, and answers the rows found. */
    private fun hooksTheList(bundle: File, label: String): List<FlagRow> {
        val patch = PatchContexts.of(slice(bundle))

        val list = patch.findFlagNameList()
        assertTrue("$label: no rows", list.rows.isNotEmpty())
        patch.applyFlagNameList(list)

        val owner = list.builder.substringBefore("->")
        val hooked = patch.classDefBy(owner).methods.single { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == list.builder }
        assertHooked(hooked, list.rows.size, label)
        for (stub in listOf("getFlagConfigNative", "getFlagIndexNative")) {
            val code = patch.method(OVERRIDE_BRIDGE, stub).implementation!!.instructions.toList()
            assertEquals("$label: $stub", Opcode.INSTANCE_OF, code.first().opcode)
            assertEquals("$label: $stub reads one int field", 1, code.count { it.opcode == Opcode.IGET })
        }
        return list.rows
    }

    /** The classes the finder reads in [bundle]: the name loader's, the schema entry, list builder candidates, their string pools, and the extension's bridge and hooks. */
    private fun slice(bundle: File): List<ClassDef> {
        val kept = mutableListOf<ClassDef>()
        val types = mutableSetOf<String>()
        FixtureDex.forEach(bundle) { dex ->
            for (clazz in dex.classes) {
                val wanted = clazz.methods.any { method ->
                    val code = method.implementation?.instructions?.toList().orEmpty()
                    code.any { (it.reference() as? StringReference)?.string in setOf(NAME_LOADER, NAME_FAILURE) } ||
                        code.any { (it.reference() as? MethodReference)?.isRecord() == true } ||
                        (AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == "Ljava/util/List;" &&
                            code.any { it.opcode == Opcode.INVOKE_DIRECT && (it.reference() as? MethodReference)?.isRowConstructor() == true })
                }
                if (!wanted) continue
                kept += ImmutableClassDef.of(clazz)
                clazz.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
                    .mapNotNull { (it.reference() as? MethodReference)?.takeIf { call -> call.isRecord() }?.definingClass }.forEach { types += it }
            }
        }
        val native = FixtureDex.withStringPools(bundle, (kept + FixtureDex.classes(bundle, types).values).distinctBy { it.type })
        return native + ExtensionDex.classDef(OVERRIDE_BRIDGE) + ExtensionDex.classDef(FLAG_NAMES)
    }

    /**
     * Each row the builder makes now has, right before its new-instance, the parameter label hook
     * and its result, then the config label hook and its result, each on the entry and the label
     * the row's constructor takes. [rows] rows, and no other call to either hook.
     */
    private fun assertHooked(method: Method, rows: Int, label: String = "stand-in") {
        val code = method.implementation!!.instructions.toList()
        val calls = code.indices.filter { code[it].opcode == Opcode.INVOKE_DIRECT && (code[it].reference() as? MethodReference)?.isRowConstructor() == true }
        assertEquals("$label: rows", rows, calls.size)
        for (call in calls) {
            val made = code[call] as FiveRegisterInstruction
            assertEquals("$label: the new-instance", Opcode.NEW_INSTANCE, code[call - 1].opcode)
            val parameter = code[call - 5] as FiveRegisterInstruction
            val config = code[call - 3] as FiveRegisterInstruction
            assertEquals("$label: the parameter hook", FLAG_PARAMETER_LABEL, code[call - 5].reference().toString())
            assertEquals("$label: the config hook", FLAG_CONFIG_LABEL, code[call - 3].reference().toString())
            assertEquals("$label: one entry for both", parameter.registerC, config.registerC)
            assertEquals("$label: the parameter label", made.registerF, parameter.registerD)
            assertEquals("$label: the config label", made.registerG, config.registerD)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[call - 4].opcode)
            assertEquals("$label: the parameter label taken back", made.registerF, (code[call - 4] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[call - 2].opcode)
            assertEquals("$label: the config label taken back", made.registerG, (code[call - 2] as OneRegisterInstruction).registerA)
        }
        val hooks = code.count { it.reference()?.toString() in setOf(FLAG_PARAMETER_LABEL, FLAG_CONFIG_LABEL) }
        assertEquals("$label: hook calls", rows * 2, hooks)
    }

    private fun MethodReference.isRecord() = name == "<init>" && parameterTypes.map(Any::toString) == RECORD_ARGS
    private fun MethodReference.isRowConstructor() = name == "<init>" && parameterTypes.joinToString("", "(", ")$returnType") == ROW_CONSTRUCTOR
    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    private fun BytecodePatchContext.method(owner: String, name: String) = classDefBy(owner).methods.single { it.name == name }
    private fun Method.shape() = implementation!!.instructions.map { "${it.opcode}${it.reference()?.let { reference -> " $reference" }.orEmpty()}" }
    private fun BytecodePatchContext.snapshot() = listOf(builder, OVERRIDE_BRIDGE).mapNotNull { classDefByOrNull(it) }
        .flatMap { clazz -> clazz.methods.map { it.toString() to it.shape() } }

    /**
     * Instagram's name loader building schema entries, the entry, a list builder shaped like 450's
     * (two kinds of row, each label falling back to "_" and a number, a null check after each),
     * the bridge readers and the hooks. Each flag breaks one thing the trace relies on.
     */
    private fun classes(
        jumpIn: Boolean = false, jumpToNew: Boolean = false, overwriteEntry: Boolean = false, twoEntries: Boolean = false,
        labelAfterRead: Boolean = false, skipConfigRead: Boolean = false,
    ): List<ClassDef> {
        val types = listOf(string, string) + List(7) { "I" } + List(3) { "Z" }
        val constructor = "invoke-direct { p0 }, Ljava/lang/Object;-><init>()V\n" + fields.mapIndexed { i, field ->
            val op = when (types[i]) { "I" -> "iput"; "Z" -> "iput-boolean"; else -> "iput-object" }
            "$op p${i + 1}, p0, $entry->$field:${types[i]}"
        }.joinToString("\n") + "\nreturn-void"
        return listOf(
            clazz(loader, methods = listOf(method(loader, "schema", emptyList(), "Ljava/lang/Object;", 14, public, """
                const-string v0, "$NAME_LOADER"
                const-string v0, "$NAME_FAILURE"
                new-instance v0, $entry
                invoke-direct/range { v0 .. v12 }, $entry-><init>(${types.joinToString("")})V
                return-object v0
            """.trimIndent()))),
            clazz(entry, fields = fields.mapIndexed { i, name -> ImmutableField(entry, name, types[i], public, null, null, null) },
                methods = listOf(
                    method(entry, "<init>", types, "V", 13, public or AccessFlags.CONSTRUCTOR.value, constructor),
                    method(entry, "id", emptyList(), "J", 3, public, "const-wide v0, 0x0\nreturn-wide v0"),
                )),
            builderClass(builder, jumpIn, jumpToNew, overwriteEntry, twoEntries, labelAfterRead, skipConfigRead),
            clazz(OVERRIDE_BRIDGE, methods = listOf("getFlagConfigNative", "getFlagIndexNative").map { name ->
                method(OVERRIDE_BRIDGE, name, listOf("Ljava/lang/Object;"), "I", 2, static, "const/4 v0, -0x1\nreturn v0")
            }),
            clazz(FLAG_NAMES, methods = listOf("parameter", "config").map { name ->
                method(FLAG_NAMES, name, listOf("Ljava/lang/Object;", string), string, 2, static, "return-object p1")
            }),
        )
    }

    private fun builderClass(
        type: String, jumpIn: Boolean = false, jumpToNew: Boolean = false, overwriteEntry: Boolean = false,
        twoEntries: Boolean = false, labelAfterRead: Boolean = false, skipConfigRead: Boolean = false,
    ): ClassDef {
        fun row(kind: String, broken: Boolean) = """
            iget-object v4, v6, $entry->name:$string
            invoke-virtual { v4 }, $string->isEmpty()Z
            move-result v0
            if-eqz v0, ${if (broken && skipConfigRead) ":config_named_$kind" else ":parameter_named_$kind"}
            iget v0, v6, $entry->index:I
            invoke-static { v0 }, $string->valueOf(I)$string
            move-result-object v1
            const-string v0, "_"
            invoke-virtual { v0, v1 }, $string->concat($string)$string
            move-result-object v4
            :parameter_named_$kind
            invoke-static { v4 }, Lfixture/Checks;->notNull(Ljava/lang/Object;)V
            ${if (broken && jumpIn) ":inside" else "nop"}
            iget-object v1, ${if (broken && twoEntries) "v7" else "v6"}, $entry->configName:$string
            ${if (broken && overwriteEntry) "const/4 v6, 0x0" else "nop"}
            ${if (broken && labelAfterRead) "const-string v4, \"set\"" else "nop"}
            invoke-virtual { v1 }, $string->isEmpty()Z
            move-result v0
            if-eqz v0, :config_named_$kind
            iget v0, v6, $entry->config:I
            invoke-static { v0 }, $string->valueOf(I)$string
            move-result-object v1
            const-string v0, "_"
            invoke-virtual { v0, v1 }, $string->concat($string)$string
            move-result-object v1
            :config_named_$kind
            ${if (broken && jumpToNew) "" else "invoke-static { v1 }, Lfixture/Checks;->notNull(Ljava/lang/Object;)V"}
            new-instance v0, Lfixture/Row$kind;
            invoke-direct { v0, v2, v3, v4, v1 }, Lfixture/Row;-><init>(J$string$string)V
        """.trimIndent()
        val code = """
            sget-object v5, $type->cache:Ljava/util/List;
            ${if (jumpIn) "if-nez v5, :inside" else "nop"}
            new-instance v5, Ljava/util/ArrayList;
            invoke-direct { v5 }, Ljava/util/ArrayList;-><init>()V
            sget-object v0, $type->entries:Ljava/util/List;
            invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
            move-result-object v7
            :loop
            invoke-interface { v7 }, Ljava/util/Iterator;->hasNext()Z
            move-result v0
            if-eqz v0, :done
            invoke-interface { v7 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
            move-result-object v6
            check-cast v6, $entry
            invoke-virtual { v6 }, $entry->id()J
            move-result-wide v2
            iget v1, v6, $entry->kind:I
            const/4 v0, 0x2
            if-ne v1, v0, :other
            ${row("A", true)}
            :add
            invoke-virtual { v5, v0 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            goto :loop
            :other
            ${row("B", false)}
            goto :add
            :done
            return-object v5
        """.trimIndent()
        return clazz(type, fields = listOf(
            ImmutableField(type, "cache", "Ljava/util/List;", static, null, null, null),
            ImmutableField(type, "entries", "Ljava/util/List;", static, null, null, null),
        ), methods = listOf(method(type, "rows", emptyList(), "Ljava/util/List;", 9, static, code)))
    }

    private fun clazz(type: String, fields: List<ImmutableField> = emptyList(), methods: List<ImmutableMethod> = emptyList()): ClassDef =
        ImmutableClassDef(type, public, "Ljava/lang/Object;", null, null, null, fields, methods)

    private fun method(owner: String, name: String, params: List<String>, returns: String, registers: Int, flags: Int, code: String): ImmutableMethod {
        val method = MutableMethod(ImmutableMethod(owner, name, params.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null)))
        method.addInstructionsWithLabels(0, code)
        return ImmutableMethod.of(method)
    }
}

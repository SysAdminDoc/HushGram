/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.developeroptions

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.*
import org.junit.Test

class OverrideWriterTest {
    private val public = AccessFlags.PUBLIC.value
    private val static = public or AccessFlags.STATIC.value
    private val nativeFlags = public or AccessFlags.NATIVE.value
    private val abstractFlags = public or AccessFlags.ABSTRACT.value
    private val user = "Lcom/instagram/common/session/UserSession;"
    private fun type(name: String, prefix: String) = "Lfixture/$prefix$name;"
    private fun model(prefix: String = "") = type("Model", prefix)

    @Test fun renamedStoreTableAndManagerClassesStillResolveTheTypedNativeWriter() {
        for (prefix in listOf("", "Renamed")) {
            val patch = PatchContexts.of(classes(prefix))
            val writer = patch.findOverrideWriter(model(prefix))
            patch.fillOverrideWriter(writer)
            assertWriter(patch, writer, model(prefix))
            assertEquals(type("Table", prefix), writer.table)
            assertEquals("${type("Delegate", prefix)}->gate(${type("Base", prefix)})$MANAGER_IMPL", writer.gate)
        }
    }

    /** 450 decodes the type in the put itself; the type stub repeats the same shift and mask. */
    @Test fun anInlineDecoderIsRepeatedInTheTypeStub() {
        val patch = PatchContexts.of(classes("", setOf("inlineDecoder")))
        val writer = patch.findOverrideWriter(model())
        patch.fillOverrideWriter(writer)
        assertEquals(TypeDecoder.Bits(48, 0x3f), writer.decoder)
        assertWriter(patch, writer, model())
    }

    @Test fun missingRenamedAndAmbiguousBoundariesRefuseBeforeAnyStubChanges() {
        val cases = listOf(
            "missing typed put" to "noPut", "two typed puts" to "twoPuts", "missing double put" to "noDouble",
            "puts on two tables" to "otherTable", "put reaches a string import" to "stringImport",
            "missing decoder" to "noDecoder", "two decoders" to "twoDecoders", "private decoder" to "privateDecoder",
            "missing remove" to "noRemove", "two removes" to "twoRemoves",
            "native table outside the store's interface" to "detachedNative", "renamed native writer" to "renamedNative",
            "native put with extra work" to "busyNative", "Java remove" to "javaRemove",
            "manager makes another table" to "javaManager", "missing store factory" to "noFactory",
            "two store factories" to "twoFactories", "table from another object" to "otherReceiver",
            "delegate overwritten before the table call" to "overwritten", "branch into the table flow" to "branchIn",
            "table kept where the put doesn't read" to "otherField", "missing gate" to "noGate", "two gates" to "twoGates",
            "gate without a native check" to "blindGate", "no native-ready check" to "noReadyCheck",
            "missing writer stub" to "noStub", "renamed manager base getter" to "renamedBaseGetter",
            "private native manager table getter" to "privateManagerGetter",
            // The arms are a convention until the put shows it: code k must reach the writer the extension uses for k.
            "string and double arms swapped" to "swappedArms", "put doesn't branch on the decoder" to "unusedDecoder",
            "decoder reads another ID than the writers" to "otherId",
            "inline decoder reads another ID than the writers" to "inlineOtherId",
            "inline decoder masked with another register" to "inlineOtherMask",
        )
        for ((case, option) in cases) {
            val patch = PatchContexts.of(classes("", setOf(option)))
            val before = bridgeCode(patch)
            val failure = runCatching { patch.fillOverrideWriter(patch.findOverrideWriter(model())) }.exceptionOrNull()
            assertTrue("$case: $failure", failure?.message?.startsWith("Open developer options: ") == true)
            if (option in setOf("unusedDecoder", "otherId", "inlineOtherId")) {
                assertEquals(case, "Open developer options: typed put doesn't send decoder code 1 only to its Z writer", failure?.message)
            }
            assertEquals(case, before, bridgeCode(patch))
        }
    }

    @Test fun swappedArmsNameTheArmThatDisagrees() {
        val failure = runCatching { PatchContexts.of(classes("", setOf("swappedArms"))).findOverrideWriter(model()) }.exceptionOrNull()
        assertEquals("Open developer options: typed put doesn't send decoder code 3 only to its Ljava/lang/String; writer", failure?.message)
    }

    @Test fun writerStubsAreAssembledWhileFindingSoFillingCantFailAfterOtherChanges() {
        val patch = PatchContexts.of(classes("", setOf("unassemblable")))
        val before = bridgeCode(patch)
        val failure = runCatching { patch.findOverrideWriter(model()) }.exceptionOrNull()
        assertEquals("Open developer options: getOverrideTableNative doesn't assemble", failure?.message)
        assertEquals(before, bridgeCode(patch))

        // A writer that was found carries every assembled body; filling only swaps them in.
        val good = PatchContexts.of(classes(""))
        val writer = good.findOverrideWriter(model())
        assertEquals(WRITER_STUBS.keys.toList(), writer.stubs.replacements.map { it.name })
        assertTrue(writer.stubs.replacements.all { it.implementation!!.instructions.any() })
        good.fillOverrideWriter(writer)
        assertWriter(good, writer, model())
    }

    @Test fun eachDeclaredFixtureResolvesInstagramsOwnTypedWriterAndNeverAStringImport() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            // Whole-app evidence first: one typed put, one store factory, and no caller of the
            // string import or the reload this writer leaves alone.
            val puts = mutableListOf<Method>()
            val stringImports = mutableListOf<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (clazz in dex.classes) for (method in clazz.methods) {
                    if (method.texts().containsAll(listOf(DEBUG_STORE, PUT_FAILURE))) puts += method
                    method.implementation?.instructions?.forEach { instruction ->
                        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEach
                        if (called.name == "importOverridesFromUser" || (called.name == "reload" &&
                                called.definingClass == TABLE_IMPL)) stringImports += "$method -> $called"
                    }
                }
            }
            val store = puts.single().definingClass
            assertEquals("callers of the string import or reload", emptyList<String>(), stringImports)
            val factories = FixtureDex.methodsWhere(bundle, { true }) { method ->
                method.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE &&
                    ((it as ReferenceInstruction).reference as? TypeReference)?.type == store } == true
            }
            assertEquals(1, factories.size)
            val factory = factories.single().definingClass

            val roots = mutableListOf<ClassDef>()
            val types = mutableSetOf("Lcom/instagram/mainactivity/InstagramMainActivity;", "Lcom/instagram/modal/ModalActivity;",
                "Lcom/instagram/base/activity/IgFragmentActivity;", user, MANAGER_IMPL, TABLE_IMPL)
            FixtureDex.forEach(bundle) { dex ->
                for (clazz in dex.classes) {
                    if (clazz.type == store || clazz.type == factory ||
                        clazz.originalName() in setOf("MobileConfigRolloutDiagFragment", "QuickExperimentEditFragment") ||
                        "Lcom/facebook/mobileconfig/MobileConfigUpdateOverridesTableCallback;" in clazz.interfaces ||
                        clazz.methods.any { method -> method.texts().any { it in setOf(OVERRIDE_TITLE, "mc_overrides.json",
                            "MobileConfigIdNameMappingLoader", RUNTIME_NOT_READY) } }) {
                        roots += ImmutableClassDef.of(clazz)
                        clazz.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }.forEach {
                            when (val reference = (it as? ReferenceInstruction)?.reference) {
                                is MethodReference -> { types += reference.definingClass; types += reference.returnType }
                                is FieldReference -> { types += reference.definingClass; types += reference.type }
                                is TypeReference -> types += reference.type
                            }
                        }
                    }
                }
            }
            val loaded = FixtureDex.classes(bundle, types)
            loaded[MANAGER_IMPL]?.superclass?.let { types += it }
            val subset = (FixtureDex.classes(bundle, types).values + roots).distinctBy { it.type }
            val patch = PatchContexts.of(subset + bridge(full = true) + projection())
            val editor = patch.findOverrideEditor()
            val reader = patch.findOverrideReader(editor)
            val writer = patch.findOverrideWriter(reader.model)
            patch.fillOverrideReader(reader, editor)
            patch.fillOverrideWriter(writer)
            assertWriter(patch, writer, reader.model)
            assertTrue(writer.tableGetter.endsWith("->getOrCreateOverridesTable()${writer.table}"))
            assertEquals("$MANAGER_IMPL->getOrCreateOverridesTable()${writer.table}", writer.managerGetter)
            // On the device build the put branches on the decoder: 1 bool, 2 long, 3 string, 4 double.
            assertEquals(mapOf(1 to "Z", 2 to "J", 3 to "Ljava/lang/String;", 4 to "D").mapValues {
                "${writer.table}->updateOverrideForParam(J${it.value})V" }, writer.dispatch)
            assertEquals("${writer.table}->removeOverrideForParam(J)V", writer.remove)
            assertTrue(writer.gate.endsWith(")$MANAGER_IMPL"))
            checked += version
        }
        assertEquals("declared build has no fixture", versions, checked)
    }

    private fun assertWriter(patch: BytecodePatchContext, writer: OverrideWriter, model: String) {
        val stubs = patch.classDefBy(OVERRIDE_BRIDGE).methods.filter { it.name in WRITER_STUBS.keys }.associateBy { it.name }
        assertEquals(WRITER_STUBS.keys, stubs.keys)
        val references = stubs.mapValues { (_, method) ->
            method.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        }
        val all = references.values.flatten()
        assertTrue(all.toString(), all.none { reference -> FORBIDDEN_WRITES.any { reference.contains("->$it(") } })
        assertTrue(all.toString(), all.none { it.contains("->updateOverrideFor") && !it.contains("->updateOverrideForParam(") })
        // The table comes from the native manager the gate unwrapped, never from the delegate itself.
        assertEquals(listOf(model, model, writer.delegate, writer.gate, MANAGER_IMPL, writer.managerGetter, TABLE_IMPL),
            references["getOverrideTableNative"])
        val table = stubs.getValue("getOverrideTableNative").implementation!!.instructions.toList()
        val gateCall = table.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == writer.gate }
        val unwrapped = (table[gateCall + 1] as OneRegisterInstruction).registerA
        val getterCall = table.single { (it as? ReferenceInstruction)?.reference?.toString() == writer.managerGetter }
        assertEquals(unwrapped, (getterCall as FiveRegisterInstruction).registerC)
        assertTrue(table.none { (it as? ReferenceInstruction)?.reference?.toString() == writer.tableGetter })
        assertEquals(DECODED_VALUES.mapValues { writer.updates.getValue(it.value) }, writer.dispatch)
        when (val decoder = writer.decoder) {
            is TypeDecoder.Call -> assertEquals(listOf(decoder.method), references["getOverrideTypeNative"])
            is TypeDecoder.Bits -> assertEquals(
                listOf(Opcode.CONST, Opcode.USHR_LONG, Opcode.LONG_TO_INT, Opcode.CONST, Opcode.AND_INT_2ADDR, Opcode.RETURN),
                stubs.getValue("getOverrideTypeNative").implementation!!.instructions.map { it.opcode },
            )
        }
        val setters = mapOf("setOverrideBooleanNative" to writer.updates.getValue("Z"), "setOverrideLongNative" to writer.updates.getValue("J"),
            "setOverrideDoubleNative" to writer.updates.getValue("D"), "setOverrideStringNative" to writer.updates.getValue("Ljava/lang/String;"),
            "removeOverrideNative" to writer.remove)
        for ((name, update) in setters) {
            assertEquals(name, listOf(TABLE_IMPL, writer.table, update), references[name])
            val code = stubs.getValue(name).implementation!!.instructions.toList()
            assertEquals(name, Opcode.INSTANCE_OF, code.first().opcode)
        }
        val registers = mapOf("getOverrideTableNative" to 3, "setOverrideBooleanNative" to 6, "setOverrideLongNative" to 6,
            "setOverrideDoubleNative" to 6, "setOverrideStringNative" to 5, "removeOverrideNative" to 4, "getOverrideTypeNative" to 3)
        assertEquals(registers, stubs.mapValues { it.value.implementation!!.registerCount })
    }

    private fun bridgeCode(patch: BytecodePatchContext) =
        patch.classDefBy(OVERRIDE_BRIDGE).methods.map { it.toString() to it.implementation?.instructions?.map(Any::toString) }

    private fun classes(prefix: String, options: Set<String> = emptySet()): List<ClassDef> {
        fun on(option: String) = option in options
        val table = type("Table", prefix); val store = type("Store", prefix); val ids = type("Ids", prefix)
        val base = type("Base", prefix); val delegate = type("Delegate", prefix); val model = model(prefix)
        val factory = type("StoreFactory", prefix); val ready = type("BugImport", prefix)
        val other = type("OtherTable", prefix)
        // Legal in a dex file, but smali can't parse it, so a stub calling it can't be assembled.
        val gateName = if (on("unassemblable")) "gate now" else "gate"
        // Instagram's put branches on the decoder's code, one typed writer per arm.
        val arm = mapOf(1 to "bool", 2 to "long", 3 to if (on("swappedArms")) "double" else "string",
            4 to if (on("swappedArms")) "string" else "double")
        val leave = if (on("unusedDecoder")) "nop" else "goto :done"
        val inline = on("inlineDecoder") || on("inlineOtherId") || on("inlineOtherMask")
        // 450's put shifts and masks the ID itself, then zeroes the long the writers pass.
        val decode = if (inline) listOf(
            "const/16 v0, 0x30", "ushr-long v1, ${if (on("inlineOtherId")) "v3" else "p1"}, v0", "const-wide/16 v3, 0x3f",
            "and-long/2addr v1, ${if (on("inlineOtherMask")) "v1" else "v3"}", "long-to-int v5, v1", "const-wide/16 v3, 0x0",
        ) else listOfNotNull(
            if (on("noDecoder")) null else "invoke-static { ${if (on("otherId")) "v3, v4" else "p1, p2"} }, $ids->type(J)I",
            if (on("noDecoder")) null else "move-result v5",
        )
        val putCode = listOfNotNull(
            "const-string v0, \"$PUT_FAILURE\"", "const-string v0, \"$DEBUG_STORE\"", "const-wide/16 v3, 0x0",
        ).plus(decode).plus(listOfNotNull(
            if (on("twoDecoders")) "invoke-static { p1, p2 }, $ids->other(J)I" else null,
            "iget-object v1, p0, $store->table:$table",
        )).plus(if (on("unusedDecoder") || on("noDecoder")) emptyList() else
            (1..4).flatMap { listOf("const/4 v0, 0x$it", "if-eq v5, v0, :${arm.getValue(it)}") } + "goto :done"
        ).plus(listOfNotNull(
            ":bool", "const/4 v2, 0x0", "invoke-interface { v1, p1, p2, v2 }, $table->updateOverrideForParam(JZ)V", leave,
            ":long", "invoke-interface { v1, p1, p2, v3, v4 }, $table->updateOverrideForParam(JJ)V", leave,
            ":double", if (on("noDouble")) null else
                "invoke-interface { v1, p1, p2, v3, v4 }, ${if (on("otherTable")) other else table}->updateOverrideForParam(JD)V", leave,
            ":string", "invoke-interface { v1, p1, p2, p3 }, $table->updateOverrideForParam(JLjava/lang/String;)V",
            if (on("stringImport")) "invoke-virtual { v1, p3 }, Lcom/facebook/mobileconfig/troubleshooting/MobileConfigOverridesWriterHolder;->importOverridesFromUser(Ljava/lang/String;)Ljava/lang/String;" else null,
            ":done", "return-void",
        )).joinToString("\n")
        val removeCode = "iget-object v0, p0, $store->table:$table\ninvoke-interface { v0, p1, p2 }, $table->removeOverrideForParam(J)V\nreturn-void"
        val storeMethods = listOfNotNull(
            if (on("noPut")) null else method(store, "put", listOf("J", "Ljava/lang/String;"), "V", 10, public, putCode),
            if (on("twoPuts")) method(store, "putAgain", listOf("J", "Ljava/lang/String;"), "V", 10, public, putCode) else null,
            if (on("noRemove")) null else method(store, "remove", listOf("J"), "V", 4, public, removeCode),
            if (on("twoRemoves")) method(store, "reset", listOf("J"), "V", 4, public, removeCode) else null,
        )
        val tableMethods = listOf("Z", "J", "D", "Ljava/lang/String;").map { declared(table, "updateOverrideForParam", listOf("J", it), "V", abstractFlags) } +
            declared(table, "removeOverrideForParam", listOf("J"), "V", abstractFlags)
        val nativeMethods = OVERRIDE_VALUES.flatMap { (value, writer) ->
            val name = if (on("renamedNative") && value == "Z") "updateOverrideForBoolean" else writer
            val wide = value == "J" || value == "D"
            val args = if (wide) "p0, p1, p2, p3, p4" else "p0, p1, p2, p3"
            listOf(declared(TABLE_IMPL, name, listOf("J", value), "V", nativeFlags),
                method(TABLE_IMPL, "updateOverrideForParam", listOf("J", value), "V", if (wide) 5 else 4, public,
                    (if (on("busyNative") && value == "Z") "nop\n" else "") + "invoke-virtual { $args }, $TABLE_IMPL->$writer(J$value)V\nreturn-void"))
        } + if (on("javaRemove")) method(TABLE_IMPL, "removeOverrideForParam", listOf("J"), "V", 3, public, "return-void")
            else declared(TABLE_IMPL, "removeOverrideForParam", listOf("J"), "V", nativeFlags)
        val managerCode = if (on("javaManager")) "invoke-virtual { p0 }, $MANAGER_IMPL->getJavaTable()Lfixture/JavaTable;\nmove-result-object v0\nreturn-object v0"
            else "invoke-virtual { p0 }, $MANAGER_IMPL->getOrCreateOverridesTableHolder()$TABLE_IMPL\nmove-result-object v0\nreturn-object v0"
        val gateCode = if (on("blindGate")) "check-cast p0, $MANAGER_IMPL\nreturn-object p0"
            else "instance-of v0, p0, $MANAGER_IMPL\nif-nez v0, :native\nconst/4 p0, 0x0\n:native\ncheck-cast p0, $MANAGER_IMPL\nreturn-object p0"
        val factoryCode = listOfNotNull(
            if (on("branchIn")) "if-eqz p0, :inside" else null,
            "const/4 v0, 0x0", "invoke-virtual { v0 }, $model->delegate()$delegate", "move-result-object v1",
            if (on("branchIn")) ":inside" else null,
            "new-instance v2, $store", "invoke-direct { v2 }, Ljava/lang/Object;-><init>()V",
            if (on("overwritten")) "const/4 v1, 0x0" else null,
            "invoke-virtual { ${if (on("otherReceiver")) "v0" else "v1"} }, $base->getOrCreateOverridesTable()$table",
            "move-result-object v3", "iput-object v3, v2, $store->${if (on("otherField")) "otherTable" else "table"}:$table",
            "return-object v2",
        ).joinToString("\n")
        val factoryMethod = method(factory, "forSession", listOf(user), store, 5, static, factoryCode)
        return listOfNotNull(
            clazz(table, flags = public or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, methods = tableMethods),
            clazz(store, fields = listOf(field(store, "table", table), field(store, "otherTable", table)), methods = storeMethods),
            clazz(ids, methods = listOf(method(ids, "type", listOf("J"), "I", 3,
                if (on("privateDecoder")) AccessFlags.PRIVATE.value or AccessFlags.STATIC.value else static, "const/4 v0, 0x0\nreturn v0")) +
                if (on("twoDecoders")) listOf(method(ids, "other", listOf("J"), "I", 3, static, "const/4 v0, 0x0\nreturn v0")) else emptyList()),
            clazz(TABLE_IMPL, interfaces = if (on("detachedNative")) emptyList() else listOf(table), methods = nativeMethods),
            clazz(base, flags = abstractFlags, methods = listOf(declared(base,
                if (on("renamedBaseGetter")) "getOrCreateTable" else "getOrCreateOverridesTable", emptyList(), table, abstractFlags))),
            clazz(MANAGER_IMPL, superclass = base, methods = listOf(
                declared(MANAGER_IMPL, "getOrCreateOverridesTableHolder", emptyList(), TABLE_IMPL, nativeFlags),
                method(MANAGER_IMPL, "getOrCreateOverridesTable", emptyList(), table, 2,
                    if (on("privateManagerGetter")) AccessFlags.PRIVATE.value else public, managerCode))),
            clazz(delegate, superclass = base, methods = listOfNotNull(
                if (on("noGate")) null else method(delegate, gateName, listOf(base), MANAGER_IMPL, 2, static, gateCode),
                if (on("twoGates")) method(delegate, "unwrap", listOf(base), MANAGER_IMPL, 2, static, gateCode) else null)),
            clazz(model, methods = listOf(method(model, "delegate", emptyList(), delegate, 2, public, "const/4 v0, 0x0\nreturn-object v0"))),
            if (on("noFactory")) null else clazz(factory, methods = listOf(factoryMethod)),
            if (on("twoFactories")) clazz(type("OtherFactory", prefix), methods = listOf(method(type("OtherFactory", prefix), "forSession",
                listOf(user), store, 5, static, factoryCode))) else null,
            // Built without smali, so a gate name smali can't parse still reaches the writer.
            clazz(ready, original = "MobileConfigBugImport", methods = listOf(ImmutableMethod(ready, "onClick", emptyList(), "V", public,
                null, null, ImmutableMethodImplementation(2, listOfNotNull(
                    if (on("noReadyCheck")) null else ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(RUNTIME_NOT_READY)),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                        ImmutableMethodReference(delegate, gateName, listOf(base), MANAGER_IMPL)),
                    ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)))),
            bridge(omit = if (on("noStub")) "removeOverrideNative" else null),
        )
    }

    private fun bridge(full: Boolean = false, omit: String? = null): ClassDef {
        val readers = if (!full) emptyList() else listOf(
            method(OVERRIDE_BRIDGE, "openOverridesNative", listOf("Ljava/lang/Object;"), "I", 2, static, "const/4 v0, 0x0\nreturn v0"),
            method(OVERRIDE_BRIDGE, "getOverrideStoreNative", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", 2, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(OVERRIDE_BRIDGE, "getOverrideFileNative", listOf("Ljava/lang/Object;"), "Ljava/io/File;", 2, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(OVERRIDE_BRIDGE, "getOverrideSchemaNative", listOf("Ljava/lang/Object;"), "Ljava/util/List;", 2, static, "const/4 v0, 0x0\nreturn-object v0"),
            method(OVERRIDE_BRIDGE, "getOverrideParameterNative", listOf("Ljava/lang/Object;"), OVERRIDE_PARAMETER, 2, static, "const/4 v0, 0x0\nreturn-object v0"))
        val writers = WRITER_STUBS.filterKeys { it != omit }.map { (name, shape) ->
            val params = Regex("L[^;]+;|[JDI]").findAll(shape.first).map { it.value }.toList()
            val registers = params.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() + 1
            val body = if (shape.second == "I") "const/4 v0, 0x0\nreturn v0" else "const/4 v0, 0x0\nreturn-object v0"
            method(OVERRIDE_BRIDGE, name, params, shape.second, registers, static, body)
        }
        return clazz(OVERRIDE_BRIDGE, methods = readers + writers)
    }

    private fun projection() = clazz(OVERRIDE_PARAMETER, methods = listOf(method(OVERRIDE_PARAMETER, "<init>",
        listOf("I", "I", "Ljava/lang/String;", "Ljava/lang/String;", "I", "J"), "V", 8, public or AccessFlags.CONSTRUCTOR.value, "return-void")))
    private fun field(owner: String, name: String, type: String) = ImmutableField(owner, name, type, public, null, null, null)
    private fun clazz(type: String, flags: Int = public, superclass: String = "Ljava/lang/Object;", fields: List<ImmutableField> = emptyList(),
                      methods: List<ImmutableMethod> = emptyList(), original: String? = null, interfaces: List<String> = emptyList()): ClassDef =
        ImmutableClassDef(type, flags, superclass, interfaces, null, null, fields + if (original == null) emptyList() else
            listOf(ImmutableField(type, "__redex_internal_original_name", "Ljava/lang/String;", static, ImmutableStringEncodedValue(original), null, null)), methods)
    private fun declared(owner: String, name: String, params: List<String>, returns: String, flags: Int) =
        ImmutableMethod(owner, name, params.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null, null)
    private fun method(owner: String, name: String, params: List<String>, returns: String, registers: Int, flags: Int, code: String): ImmutableMethod {
        val method = MutableMethod(ImmutableMethod(owner, name, params.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null)))
        method.addInstructionsWithLabels(0, code)
        return ImmutableMethod.of(method)
    }
    private fun Method.texts() = implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
}

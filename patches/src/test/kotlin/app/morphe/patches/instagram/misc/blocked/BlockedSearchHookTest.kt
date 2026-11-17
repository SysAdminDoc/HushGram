/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.blocked

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Search blocked accounts (#731): the filter goes on the blocked list's accounts where its model
 * build starts walking them, the box goes in once the screen keeps its RecyclerView, and the five
 * bridges reach the screen's page loader, its state's three flags and the account's two names.
 */
class BlockedSearchHookTest {
    private val extension get() = ExtensionDex.classDef(BLOCKED_SEARCH)

    @Test
    fun theHooksAndBridgesAreInTheExtension() {
        val declared = extension.methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val wanted = listOf(
            BLOCKED_FILTER.substringAfter("->"),
            BLOCKED_ATTACH.substringAfter("->"),
            "redraw(Ljava/lang/Object;)V",
            "loadMore(Ljava/lang/Object;)V",
            "pages(Ljava/lang/Object;)I",
            "username(Ljava/lang/Object;)Ljava/lang/String;",
            "fullName(Ljava/lang/Object;)Ljava/lang/String;",
        )
        for (signature in wanted) assertTrue("$signature is not in the extension: $declared", signature in declared)
    }

    /** The pages bridge answers the bits the extension's pager reads. */
    @Test
    fun thePageBitsMatchTheExtension() {
        assertEquals(PAGES_MORE, ExtensionDex.intConstant(BLOCKED_SEARCH, "MORE"))
        assertEquals(PAGES_LOADING, ExtensionDex.intConstant(BLOCKED_SEARCH, "LOADING"))
        assertEquals(PAGES_FAILED, ExtensionDex.intConstant(BLOCKED_SEARCH, "FAILED"))
    }

    /** A build without the blocked list, or without the extension, fails before anything changes. */
    @Test
    fun aBuildWithoutTheListFailsBeforeAnythingChanges() {
        val bare = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(extension)).findBlockedSearch() }
        assertTrue(bare.message, bare.message!!.contains("expected one class naming itself $BLOCKED_FACTORY_NAME, found 0"))

        val noExtension = assertThrows(PatchException::class.java) { PatchContexts.of(emptyList()).findBlockedSearch() }
        assertTrue(noExtension.message, noExtension.message!!.contains("the extension has no $BLOCKED_SEARCH"))

        val stubless = ImmutableClassDef(
            BLOCKED_SEARCH, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(),
            extension.methods.filter { it.name != "pages" }.map(ImmutableMethod::of),
        )
        val noPages = assertThrows(PatchException::class.java) { PatchContexts.of(listOf(stubless)).findBlockedSearch() }
        assertTrue(noPages.message, noPages.message!!.contains("the extension has no public static pages(Ljava/lang/Object;)I"))
    }

    /**
     * In each declared build and the other ABIs the blocked list is found on all three sides, the
     * filter goes in front of the walk and takes its label, the box goes in after the list is kept,
     * every jump keeps its place, the stock code is all still there, and each bridge is written
     * against that build's own classes and fields.
     */
    @Test
    fun everyBuildFiltersTheBlockedList() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the others", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val name = bundle.parentFile?.name?.takeIf { bundle.name == "base.apk" } ?: bundle.name
            val context = PatchContexts.of(sliced(bundle, name) + extension)

            val targets = context.findBlockedSearch()
            val build = ImmutableMethod.of(targets.build)
            val stock = build.code()
            assertEquals("$name: the list is the build's second parameter", build.parameterRegisterNumber(1), targets.list)
            val walk = stock[targets.iterator]
            assertEquals("$name: the walk", Opcode.INVOKE_INTERFACE, kind(walk.opcode))
            assertEquals("$name: the walk", "Ljava/util/List;->iterator()Ljava/util/Iterator;", walk.referenceText())
            assertEquals("$name: the walk's list", targets.list, walk.namedRegisters().single())
            val buildTargets = build.jumpTargets()

            assertNotEquals("$name: two names", targets.username.name, targets.fullName.name)
            for (field in listOf(targets.username, targets.fullName)) {
                assertEquals("$name: the account's", targets.item, field.definingClass)
                assertEquals("$name: a text field", "Ljava/lang/String;", field.type)
            }

            val created = ImmutableMethod.of(targets.created)
            val createdStock = created.code()
            assertEquals("$name: the screen's", targets.fragment, created.definingClass)
            val keep = createdStock[targets.attachAt - 1]
            assertEquals("$name: the list is kept", Opcode.IPUT_OBJECT, keep.opcode)
            assertEquals("$name: as a RecyclerView", "Landroidx/recyclerview/widget/RecyclerView;", (keep.reference() as FieldReference).type)
            assertEquals("$name: from the hook's register", targets.recycler, (keep as TwoRegisterInstruction).registerA)
            assertEquals("$name: on the screen", created.localRegisterCount(), keep.registerB)
            val createdTargets = created.jumpTargets()
            assertTrue("$name: nothing jumps to the box", targets.attachAt !in createdTargets)

            assertEquals(listOf("redraw", "loadMore", "pages", "username", "fullName"), targets.bridges.map { it.name })
            val redraw = targets.bridges.single { it.name == "redraw" }.body
            assertTrue("$name: redraw builds the model", targets.build.let { "${it.definingClass}->${it.name}(" } in redraw)
            assertTrue("$name: redraw shows it on the screen", "${targets.fragment}->" in redraw)
            val pages = targets.bridges.single { it.name == "pages" }.body
            assertEquals("$name: pages reads three flags", 3, Regex("iget-boolean").findAll(pages).count())
            assertTrue("$name: loadMore starts a page", "invoke-virtual { v0 }" in targets.bridges.single { it.name == "loadMore" }.body)
            assertTrue("$name: username", "->${targets.username.name}:" in targets.bridges.single { it.name == "username" }.body)
            assertTrue("$name: fullName", "->${targets.fullName.name}:" in targets.bridges.single { it.name == "fullName" }.body)

            addBlockedSearch(targets)

            val patched = ImmutableMethod.of(targets.build)
            val code = patched.code()
            val at = targets.iterator
            assertEquals("$name: the filter", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            assertEquals("$name: the filter", BLOCKED_FILTER, code[at].referenceText())
            assertEquals(
                "$name: hands over the list", listOf(targets.list, 1),
                (code[at] as RegisterRangeInstruction).let { listOf(it.startRegister, it.registerCount) },
            )
            assertEquals("$name: the answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
            assertEquals("$name: back in the list's register", targets.list, (code[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$name: then the walk", walk.referenceText(), code[at + 2].referenceText())
            assertEquals(
                "$name: every jump keeps its place, one to the walk now landing on the filter",
                buildTargets.map { if (it > at) it + 2 else it }.toSet(),
                patched.jumpTargets(),
            )
            assertEquals("$name: the build's own code", stock.map { it.describe() }, (code.take(at) + code.drop(at + 2)).map { it.describe() })

            val createdPatched = ImmutableMethod.of(targets.created)
            val createdCode = createdPatched.code()
            val box = createdCode[targets.attachAt]
            assertEquals("$name: the box", Opcode.INVOKE_STATIC, box.opcode)
            assertEquals("$name: the box", BLOCKED_ATTACH, box.referenceText())
            assertEquals(
                "$name: the screen, its view and its list",
                listOf(created.localRegisterCount(), created.parameterRegisterNumber(0), targets.recycler),
                box.namedRegisters(),
            )
            assertEquals(
                "$name: every jump keeps its place",
                createdTargets.map { if (it >= targets.attachAt) it + 1 else it }.toSet(),
                createdPatched.jumpTargets(),
            )
            assertEquals(
                "$name: onViewCreated's own code",
                createdStock.map { it.describe() },
                (createdCode.take(targets.attachAt) + createdCode.drop(targets.attachAt + 1)).map { it.describe() },
            )

            val written = context.mutableClassDefBy(BLOCKED_SEARCH).methods
            for (bridge in targets.bridges) {
                val method = written.single { it.name == bridge.name && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") }
                assertEquals("$name: ${bridge.name}'s registers", bridge.registers, method.implementation!!.registerCount)
                assertTrue("$name: ${bridge.name} is written", method.code().size > 2)
            }
            val references = { bridge: String -> written.single { it.name == bridge }.code().mapNotNull { it.referenceText() } }
            assertTrue("$name: redraw calls the build", references("redraw").any { it.startsWith("${targets.build.definingClass}->${targets.build.name}(") })
            assertEquals("$name: redraw reads the loader, factory, state, store and accounts", 5, references("redraw").count { it.contains("->") && !it.contains("(") })
            assertEquals("$name: pages reads the state's flags", 3, written.single { it.name == "pages" }.code().count { it.opcode == Opcode.IGET_BOOLEAN })
            assertTrue("$name: loadMore starts a page", written.single { it.name == "loadMore" }.code().any { it.opcode == Opcode.INVOKE_VIRTUAL })
            assertEquals("$name: username", "${targets.item}->${targets.username.name}:Ljava/lang/String;", references("username").last())
            assertEquals("$name: fullName", "${targets.item}->${targets.fullName.name}:Ljava/lang/String;", references("fullName").last())
        }
    }

    /**
     * The classes the patch reads, from one build: the factory and the page loader by their
     * strings, the classes the loader keeps and the account the build casts to, the account's JSON
     * reader by its writes of the account's text, and the string pools all of them ask.
     */
    private fun sliced(bundle: File, name: String): List<ClassDef> {
        val anchored = FixtureDex.classesLoadingAny(bundle, setOf(BLOCKED_FACTORY_NAME, BLOCKED_LIST_PATH))
        val build = anchored.flatMap { it.methods }.filter { method ->
            val types = method.parameterTypes.map(CharSequence::toString)
            !AccessFlags.STATIC.isSet(method.accessFlags) && types.size == 2 && types[1] == "Ljava/util/List;" &&
                anchored.any { it.type == method.definingClass && it.methods.any { m -> m.name == "<clinit>" } } &&
                method.code().any { it.referenceText() == "Ljava/util/List;->iterator()Ljava/util/Iterator;" }
        }
        val walk = build.singleOrNull() ?: throw AssertionError("$name: expected one model build, found ${build.map { "${it.definingClass}->${it.name}" }}")
        val code = walk.code()
        val item = code.withIndex().first { it.value.opcode == Opcode.CHECK_CAST && code.getOrNull(it.index - 2)?.referenceText()?.startsWith("Ljava/util/Iterator;->next(") == true }
            .value.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        val state = walk.parameterTypes[0].toString()
        val loaders = anchored.filter { holder -> holder.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == state } }
        val wanted = (loaders.flatMap { it.fields }.map { it.type } + item + state).filter { it.startsWith("L") }.toSet()
        val have = anchored.mapTo(HashSet()) { it.type }
        val more = LinkedHashMap<String, ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in have || classDef.type in more) continue
                val writesItem = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                            field.definingClass == item && field.type == "Ljava/lang/String;"
                        }
                    } == true
                }
                if (classDef.type in wanted || writesItem) more[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        return FixtureDex.withStringPools(bundle, (anchored + more.values).distinctBy { it.type }).distinctBy { it.type }
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun kind(opcode: Opcode) = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        Opcode.INVOKE_INTERFACE_RANGE -> Opcode.INVOKE_INTERFACE
        else -> opcode
    }

    /** The instruction without its branch offset, a widened goto reading as a goto. */
    private fun Instruction.describe(): String = buildString {
        append("${if (opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) Opcode.GOTO else opcode}")
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        if (this@describe is FiveRegisterInstruction) append(" {$registerCount: v$registerC v$registerD v$registerE v$registerF v$registerG}")
        if (this@describe is RegisterRangeInstruction) append(" {v$startRegister..${startRegister + registerCount}}")
        referenceText()?.let { append(" $it") }
    }
}

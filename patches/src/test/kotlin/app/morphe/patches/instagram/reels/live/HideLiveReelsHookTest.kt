/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.live

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
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
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
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

class HideLiveReelsHookTest {
    private val item = "Lfixture/ClipsItem;"
    private val keyType = "Lfixture/LiveKey;"
    private val keyInterface = "Lfixture/PartKey;"
    private val holder = "Lfixture/LivePreview;"
    private val other = "Lfixture/OtherPart;"
    private val useCase = "Lfixture/LivePreviewUseCase;"
    private val part = "Lfixture/LivePart;"
    private val tree = "Lfixture/Tree;"
    private val fallback = "Lfixture/Fallback;"
    private val function0 = "Lkotlin/jvm/functions/Function0;"
    private val objectType = "Ljava/lang/Object;"
    private val stringType = "Ljava/lang/String;"
    private val listType = "Ljava/util/List;"
    private val dataSource = "Lfixture/ClipsDataSource;"
    private val viewer = "Lfixture/ClipsViewer;"
    private val kindType = "Lfixture/ClipsItemKind;"
    private val adapter = "Lfixture/ClipsViewerAdapter;"

    /** The page filter and the stub the patch fills are in the LiveReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(LIVE_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val hooks = listOf(LIVE_PAGE_FILTER, LIVE_ADDED_FILTER).map { it.substringAfter("->") }
        for (wanted in hooks + "livePreview(Ljava/lang/Object;)Ljava/lang/Object;" + "placeholder(Ljava/lang/Object;)Z") {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /**
     * The key is the static field the marked method reads straight into the item's part lookup (a
     * part read through another register doesn't count), the check is the one the method runs on the
     * part it gets back, and the stub asks an item for that part, runs the same check, and answers
     * null for anything else.
     */
    @Test
    fun theKeyIsTheOneTheLivePreviewCodeAsksForAndTheStubIsFilled() {
        val context = PatchContexts.of(classes())

        val live = context.findLivePreviewKey(item)
        context.fillLivePreview(item, live)

        assertEquals("$holder->KEY:$keyType", live.key.toString())
        assertEquals("$item->part($keyInterface)$objectType", live.lookup.toString())
        assertEquals(part, live.check.part)
        assertEquals("$part->tree:$tree", live.check.tree.toString())
        assertEquals("$tree->read(I$function0)$objectType", live.check.read.toString())
        assertEquals(0xeae4559, live.check.id)
        assertEquals("$fallback-><init>(${objectType}I)V", live.check.fallback.toString())
        assertEquals(0x31, live.check.fallbackCase)
        assertStubFilled("the stand-in", context, item, live)
    }

    /** A case loaded at the top of the method, before the lookup, is still the fallback's case. */
    @Test
    fun aCaseLoadedBeforeTheLookupIsRead() {
        val context = PatchContexts.of(classes(caseFirst = true))

        val live = context.findLivePreviewKey(item)
        context.fillLivePreview(item, live)

        assertEquals(0, live.check.fallbackCase)
        assertEquals(0xeae4559, live.check.id)
        assertStubFilled("the case-first stand-in", context, item, live)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(marks = 0) to "expected one method marked $LIVE_PREVIEW_STATE, found 0",
            classes(marks = 2) to "expected one method marked $LIVE_PREVIEW_STATE, found 2",
            classes(asks = false) to "doesn't ask a Reels item ($item) for a part by a key",
            classes(twoKeys = true) to "asks a Reels item for more than one part",
            classes(keyPublic = false) to "$holder->KEY:$keyType isn't a public static field the extension can read",
            classes(lookupPublic = false) to "$item->part($keyInterface)$objectType isn't a public method the extension can call",
            classes(keyImplements = false) to "$holder->KEY:$keyType is a $keyType, not the $keyInterface part takes",
            classes(checks = false) to "doesn't check the part part answers for a String it reads with a fallback",
            classes(caseMerged = true) to "doesn't check the part part answers for a String it reads with a fallback",
            classes(treePublic = false) to "$part->tree:$tree isn't a public field the extension can read",
            classes(fallbackPublic = false) to "$fallback-><init>(${objectType}I)V isn't a public constructor the extension can call",
            classes(extension = false) to "the extension has no $LIVE_REELS",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) {
                context.fillLivePreview(item, context.findLivePreviewKey(item))
            }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /**
     * On the declared build and each other build of 454, the key is the one static field the method
     * marked [LIVE_PREVIEW_STATE] reads straight into a lookup on the item the Reels converter
     * answers, read right before that lookup, and its type is the key type the lookup takes.
     */
    @Test
    fun eachBuildFindsTheLivePreviewKey() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val converter = FixtureDex.classesHolding(bundle, TO_CLIPS_ITEM).flatMap { it.methods }
                .single { method -> method.code().any { it.string() == TO_CLIPS_ITEM } }
            val itemType = converter.returnType
            val marked = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.stringSection.none { it.endsWith("_$LIVE_PREVIEW_STATE") }) return@forEach
                for (classDef in dex.classes) {
                    if (classDef.methods.any { LIVE_PREVIEW_STATE in it.markers() }) marked += ImmutableClassDef.of(classDef)
                }
            }
            val state = marked.flatMap { it.methods }.single { LIVE_PREVIEW_STATE in it.markers() }
            val read = state.code().filter { it.opcode == Opcode.SGET_OBJECT || it.opcode == Opcode.IGET_OBJECT }
                .map { it.reference() as FieldReference }
            // The check's part, its fallback and the tree it reads, so the stub's targets are there to check.
            val made = state.code().filter { it.opcode == Opcode.CHECK_CAST || it.opcode == Opcode.NEW_INSTANCE }
                .map { (it.reference() as TypeReference).type }
            val types = read.map { it.definingClass } + read.map { it.type } + made + itemType
            val kept = (marked + FixtureDex.classes(bundle, types.toSet()).values).distinctBy { it.type } +
                ExtensionDex.classDef(LIVE_REELS)
            val context = PatchContexts.of(kept)

            val live = context.findLivePreviewKey(itemType)
            context.fillLivePreview(itemType, live)

            assertEquals("${bundle.name}: the lookup's class", itemType, live.lookup.definingClass)
            val taken = live.lookup.parameterTypes.single().toString()
            val keyClass = kept.single { it.type == live.key.type }
            assertTrue(
                "${bundle.name}: ${live.key.type} is the key type ${live.lookup.name} takes",
                live.key.type == taken || taken in keyClass.interfaces,
            )
            val at = state.code().indexOfFirst { (it.reference() as? FieldReference)?.toString() == live.key.toString() }
            assertTrue("${bundle.name}: the marked method reads ${live.key}", at >= 0)
            assertEquals("${bundle.name}: the lookup right after the key", live.lookup.toString(), state.code()[at + 1].referenceText())
            assertEquals("${bundle.name}: the part's cast right after the lookup", live.check.part, state.code()[at + 3].referenceText())
            assertEquals("${bundle.name}: the read the check makes", stringType, run {
                val readAt = state.code().indexOfFirst { it.referenceText() == live.check.read.toString() }
                state.code()[readAt + 2].referenceText()
            })
            assertStubFilled(bundle.name, context, itemType, live)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    /**
     * The placeholder is told the way the adapter tells it: the item's kind read and compared with
     * the static placeholder kind straight after. A compare of another field, or one a jump lands in
     * the middle of, doesn't count, and the stub answers true only for an item of that kind.
     */
    @Test
    fun thePlaceholderIsToldTheWayTheAdapterTellsItAndTheStubIsFilled() {
        val context = PatchContexts.of(placeholderClasses())

        val found = context.findReelsPlaceholder(item)
        context.fillReelsPlaceholder(item, found)

        assertEquals("$item->kind:$kindType", found.kind.toString())
        assertEquals("$kindType->PLACEHOLDER:$kindType", found.placeholder.toString())
        assertPlaceholderStubFilled("the stand-in", context, item, found)
    }

    /** An adapter the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun anAdapterThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            placeholderClasses(marked = false) to "expected a method marked $REELS_PLACEHOLDER, found none",
            placeholderClasses(compares = false) to "don't compare a Reels item's ($item) kind with a placeholder's",
            placeholderClasses(twoWays = true) to "tell the placeholder in more than one way",
            placeholderClasses(kindPublic = false) to "$item->kind:$kindType isn't a public field the extension can read",
            placeholderClasses(placeholderPublic = false) to "$kindType->PLACEHOLDER:$kindType isn't a public static field the extension can read",
            placeholderClasses(extension = false) to "the extension has no static placeholder(Object)boolean to fill",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findReelsPlaceholder(item) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /**
     * On the declared build and each other build of 454, the adapter's placeholder compare reads a
     * field of the item the Reels converter answers, and the stub is filled with it.
     */
    @Test
    fun eachBuildFindsThePlaceholder() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val converter = FixtureDex.classesHolding(bundle, TO_CLIPS_ITEM).flatMap { it.methods }
                .single { method -> method.code().any { it.string() == TO_CLIPS_ITEM } }
            val itemType = converter.returnType
            val marked = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.stringSection.none { it.endsWith("_$REELS_PLACEHOLDER") }) return@forEach
                for (classDef in dex.classes) {
                    if (classDef.methods.any { REELS_PLACEHOLDER in it.markers() }) marked += ImmutableClassDef.of(classDef)
                }
            }
            val read = marked.flatMap { it.methods }.filter { REELS_PLACEHOLDER in it.markers() }
                .flatMap { it.code() }.mapNotNull { it.reference() as? FieldReference }
            val types = read.map { it.definingClass } + read.map { it.type } + itemType
            val kept = (marked + FixtureDex.classes(bundle, types.toSet()).values).distinctBy { it.type } +
                ExtensionDex.classDef(LIVE_REELS)
            val context = PatchContexts.of(kept)

            val found = context.findReelsPlaceholder(itemType)
            context.fillReelsPlaceholder(itemType, found)

            assertEquals("${bundle.name}: the kind is the item's", itemType, found.kind.definingClass)
            assertEquals("${bundle.name}: the placeholder is a kind", found.kind.type, found.placeholder.type)
            assertPlaceholderStubFilled(bundle.name, context, itemType, found)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun assertPlaceholderStubFilled(what: String, context: BytecodePatchContext, itemType: String, found: ReelsPlaceholder) {
        val stub = context.mutableClassDefBy(LIVE_REELS).methods.single { it.name == "placeholder" }
        assertEquals("$what: the stub's registers", 3, stub.implementation!!.registerCount)
        assertEquals(
            "$what: the stub",
            listOf(
                Opcode.INSTANCE_OF to itemType, Opcode.IF_EQZ to null, Opcode.CHECK_CAST to itemType,
                Opcode.IGET_OBJECT to found.kind.toString(), Opcode.SGET_OBJECT to found.placeholder.toString(),
                Opcode.IF_NE to null, Opcode.CONST_4 to null, Opcode.RETURN to null, Opcode.CONST_4 to null, Opcode.RETURN to null,
            ),
            stub.code().map { it.opcode to it.referenceText() },
        )
        val answers = stub.code().filter { it.opcode == Opcode.CONST_4 }.map { (it as NarrowLiteralInstruction).narrowLiteral }
        assertEquals("$what: true for the placeholder, false for the rest", listOf(1, 0), answers)
    }

    /** The data source's add and insert hand their list to the extension first thing, with its module name and size. */
    @Test
    fun theDataSourcesAddAndInsertAreFilteredFirstThing() {
        val stock = dataSourceClasses()
        val context = PatchContexts.of(stock)

        val sites = context.findReelsItemInserts()
        context.filterReelsItemInserts(sites)

        assertEquals(dataSource, sites.dataSource)
        assertEquals("$dataSource->module:$stringType", sites.moduleField.toString())
        assertEquals("$dataSource->size()I", sites.size.toString())
        assertEquals(listOf("add($listType)V" to 0, "insert(I$listType)Z" to 1), sites.inserts)
        assertInsertsFiltered("the stand-in", stock.single { it.type == dataSource }, context, sites)
    }

    /** A data source the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aDataSourceThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            dataSourceClasses(addMarks = 0) to "expected one method marked $REELS_ADD_ITEMS, found 0",
            dataSourceClasses(addMarks = 2) to "expected one method marked $REELS_ADD_ITEMS, found 2",
            dataSourceClasses(insertElsewhere = true) to "aren't on one class",
            dataSourceClasses(keepsModule = false) to "constructor to keep one module name from getModuleName, found []",
            dataSourceClasses(moduleWrittenLater = true) to "$dataSource->module:$stringType is written by [$dataSource->reset()V]",
            dataSourceClasses(sizeDeclared = false) to "$dataSource doesn't answer its own size()I",
            dataSourceClasses(addLocals = 2) to "$dataSource->add($listType)V has 2 local register(s), needs 3",
            dataSourceClasses(addLoops = true) to "$dataSource->add($listType)V jumps back to its first instruction",
            dataSourceClasses(extension = false) to "the extension has no static added(List, String, int)List",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findReelsItemInserts() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /**
     * On the declared build and each other build of 454, the add and the insert are on one data source
     * whose constructor keeps the viewer's module name, both take the hook first thing, and the rest of
     * their code is Instagram's.
     */
    @Test
    fun eachBuildFiltersTheDataSourcesAddAndInsert() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        val names = setOf(REELS_ADD_ITEMS, REELS_INSERT_ITEMS)
        var checked = 0
        for (bundle in bundles) {
            val marked = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                if (dex.stringSection.none { string -> names.any { string.endsWith("_$it") } }) return@forEach
                for (classDef in dex.classes) {
                    if (classDef.methods.any { method -> method.markers().any { it in names } }) marked += ImmutableClassDef.of(classDef)
                }
            }
            val kept = marked.distinctBy { it.type }
            val context = PatchContexts.of(kept + ExtensionDex.classDef(LIVE_REELS))

            val sites = context.findReelsItemInserts()
            context.filterReelsItemInserts(sites)

            assertEquals("${bundle.name}: the module name's type", stringType, sites.moduleField.type)
            assertEquals("${bundle.name}: the size", "${sites.dataSource}->size()I", sites.size.toString())
            assertInsertsFiltered(bundle.name, kept.single { it.type == sites.dataSource }, context, sites)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    /**
     * Each of [sites]' inserts starts with the hook: this read for its size and module name, the list
     * handed to the filter with them, and the answer put back in the list's register. Instagram's own
     * code follows unchanged.
     */
    private fun assertInsertsFiltered(what: String, stock: ClassDef, context: BytecodePatchContext, sites: ReelsItemInserts) {
        fun Method.descriptor() = "$name(${parameterTypes.joinToString("")})$returnType"
        val patched = context.mutableClassDefBy(sites.dataSource).methods
        for ((descriptor, listIndex) in sites.inserts) {
            val before = stock.methods.single { it.descriptor() == descriptor }
            val after = patched.single { it.descriptor() == descriptor }
            val code = after.code()
            assertEquals(
                "$what: $descriptor's hook",
                listOf(
                    Opcode.MOVE_OBJECT_FROM16 to null, Opcode.INVOKE_VIRTUAL to sites.size.toString(), Opcode.MOVE_RESULT to null,
                    Opcode.IGET_OBJECT to sites.moduleField.toString(), Opcode.MOVE_OBJECT_FROM16 to null,
                    Opcode.INVOKE_STATIC to LIVE_ADDED_FILTER, Opcode.MOVE_RESULT_OBJECT to null, Opcode.MOVE_OBJECT_FROM16 to null,
                ),
                code.take(8).map { it.opcode to it.referenceText() },
            )
            val list = after.parameterRegisterNumber(listIndex)
            val self = code[0] as TwoRegisterInstruction
            val module = code[3] as TwoRegisterInstruction
            val handed = code[4] as TwoRegisterInstruction
            val filter = code[5] as FiveRegisterInstruction
            val back = code[7] as TwoRegisterInstruction
            assertEquals("$what: $descriptor reads this", after.localRegisterCount(), self.registerB)
            assertEquals("$what: $descriptor asks this for its size", self.registerA, (code[1] as FiveRegisterInstruction).registerC)
            assertEquals("$what: $descriptor reads the module name off this", self.registerA, module.registerB)
            assertEquals("$what: $descriptor hands the list in", list, handed.registerB)
            assertEquals(
                "$what: $descriptor hands the filter the list, the module name and the size",
                listOf(handed.registerA, module.registerA, (code[2] as OneRegisterInstruction).registerA),
                listOf(filter.registerC, filter.registerD, filter.registerE),
            )
            assertEquals("$what: $descriptor puts the answer in the list's place", list, back.registerA)
            assertEquals("$what: $descriptor's answer is the filter's", (code[6] as OneRegisterInstruction).registerA, back.registerB)
            assertEquals("$what: $descriptor's own code is kept", kinds(before.code()), kinds(code.drop(8)))
        }
    }

    /** Opcodes as a comparison of code reads them: a widened goto is a goto, and alignment nops don't count. */
    private fun kinds(code: List<Instruction>) = code.map {
        if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO else it.opcode
    }.filter { it != Opcode.NOP }

    private fun assertStubFilled(what: String, context: BytecodePatchContext, itemType: String, live: LivePreviewKey) {
        val stub = context.mutableClassDefBy(LIVE_REELS).methods.single { it.name == "livePreview" }
        assertEquals("$what: the stub's registers", 5, stub.implementation!!.registerCount)
        val check = live.check
        assertEquals(
            "$what: the stub",
            listOf(
                Opcode.INSTANCE_OF to itemType, Opcode.IF_NEZ to null, Opcode.CONST_4 to null, Opcode.RETURN_OBJECT to null,
                Opcode.CHECK_CAST to itemType, Opcode.SGET_OBJECT to live.key.toString(),
                Opcode.INVOKE_VIRTUAL to live.lookup.toString(), Opcode.MOVE_RESULT_OBJECT to null,
                Opcode.INSTANCE_OF to check.part, Opcode.IF_EQZ to null, Opcode.CHECK_CAST to check.part,
                Opcode.IGET_OBJECT to check.tree.toString(), Opcode.IF_EQZ to null,
                Opcode.NEW_INSTANCE to check.fallback.definingClass, Opcode.CONST to null,
                Opcode.INVOKE_DIRECT to check.fallback.toString(), Opcode.CONST to null,
                Opcode.INVOKE_INTERFACE to check.read.toString(), Opcode.MOVE_RESULT_OBJECT to null,
                Opcode.INSTANCE_OF to stringType, Opcode.IF_EQZ to null, Opcode.RETURN_OBJECT to null,
            ),
            stub.code().map { it.opcode to it.referenceText() },
        )
        val literals = stub.code().filter { it.opcode == Opcode.CONST }.map { (it as NarrowLiteralInstruction).narrowLiteral }
        assertEquals("$what: the fallback's case, then the id", listOf(check.fallbackCase, check.id), literals)
    }

    // ---- stand-ins shaped like Instagram 454's --------------------------------------------------

    /**
     * The item with its part lookup; the key's interface, its type and the class holding it; another
     * part's key; the live part with its tree, the tree's read and the fallback the read is handed;
     * the use case whose marked method asks the item for the live part first, checks it the way 454
     * does, and asks for another part later; and the extension.
     */
    private fun classes(
        marks: Int = 1,
        asks: Boolean = true,
        twoKeys: Boolean = false,
        keyPublic: Boolean = true,
        lookupPublic: Boolean = true,
        keyImplements: Boolean = true,
        checks: Boolean = true,
        treePublic: Boolean = true,
        fallbackPublic: Boolean = true,
        extension: Boolean = true,
        caseFirst: Boolean = false,
        caseMerged: Boolean = false,
    ): List<ClassDef> {
        val partClass = ImmutableClassDef(
            part, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, objectType, null, null, null,
            listOf(ImmutableField(part, "tree", tree, (if (treePublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or AccessFlags.FINAL.value, null, null, null)),
            null,
        )
        val treeClass = ImmutableClassDef(
            tree, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            objectType, null, null, null, null,
            listOf(
                ImmutableMethod(
                    tree, "read", listOf("I", function0).map { ImmutableMethodParameter(it, null, null) }, objectType,
                    AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
                ),
            ),
        )
        val fallbackClass = classDef(
            fallback,
            listOf(method(fallback, "<init>", listOf(objectType, "I"), "V", 0, public = fallbackPublic, body = "return-void")),
            interfaces = listOf(function0),
        )
        val itemClass = classDef(item, listOf(
            method(item, "part", listOf(keyInterface), objectType, 1, public = lookupPublic, body = """
                const/4 v0, 0x0
                return-object v0
            """),
        ))
        val keyInterfaceClass = ImmutableClassDef(
            keyInterface, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
            objectType, null, null, null, null, null,
        )
        val keyClass = classDef(keyType, emptyList(), interfaces = if (keyImplements) listOf(keyInterface) else emptyList())
        val holderClass = classDef(holder, emptyList(), staticFields = listOf("KEY" to keyType), staticPublic = keyPublic)
        val otherClass = classDef(other, emptyList(), staticFields = listOf("KEY" to keyType))
        fun useCaseClass(type: String) = classDef(type, listOf(method(type, "state", listOf(item), objectType, 5, body = buildString {
            if (caseFirst || caseMerged) {
                // 386014759's shape: the fallback's case is loaded at the top, long before the lookup.
                appendLine("const/4 v4, 0x0")
            }
            if (caseMerged) {
                // Two ways in with two cases, so the case at the fallback can't be known.
                appendLine("if-eqz p1, :merged")
                appendLine("const/4 v4, 0x1")
                appendLine(":merged")
            }
            appendLine("const-string v0, \"android_purge_26_q3_$LIVE_PREVIEW_STATE\"")
            if (asks) {
                appendLine("sget-object v0, $holder->KEY:$keyType")
                appendLine("invoke-virtual { p1, v0 }, $item->part($keyInterface)$objectType")
                appendLine("move-result-object v1")
            }
            if (asks && checks) {
                // 454's shape: the part is cast, its tree read, and the tree asked for the broadcast's id
                // with a fallback built from the part, the answer cast to a String.
                appendLine("check-cast v1, $part")
                appendLine("if-eqz v1, :none")
                appendLine("iget-object v2, v1, $part->tree:$tree")
                val case = if (caseFirst || caseMerged) "v4" else "v0"
                if (case == "v0") appendLine("const/16 v0, 0x31")
                appendLine("new-instance v3, $fallback")
                appendLine("invoke-direct { v3, v1, $case }, $fallback-><init>(${objectType}I)V")
                appendLine("const v0, 0xeae4559")
                appendLine("invoke-interface { v2, v0, v3 }, $tree->read(I$function0)$objectType")
                appendLine("move-result-object v0")
                appendLine("check-cast v0, $stringType")
                appendLine(":none")
            }
            // A later read of another part moves the key to another register first, so it isn't read straight into a lookup.
            appendLine("sget-object v0, $other->KEY:$keyType")
            appendLine("move-object v1, v0")
            appendLine("invoke-virtual { p1, v1 }, $item->part($keyInterface)$objectType")
            if (twoKeys) {
                appendLine("sget-object v0, $other->KEY:$keyType")
                appendLine("invoke-virtual { p1, v0 }, $item->part($keyInterface)$objectType")
            }
            appendLine("move-result-object v1")
            appendLine("return-object v1")
        })))
        val useCaseClasses = listOf(useCase, "Lfixture/OtherUseCase;").take(marks).map(::useCaseClass)
        return listOf(itemClass, keyInterfaceClass, keyClass, holderClass, otherClass, partClass, treeClass, fallbackClass) + useCaseClasses +
            if (extension) listOf(ExtensionDex.classDef(LIVE_REELS)) else emptyList()
    }

    /**
     * The Reels viewer's data source shaped like 454's: a constructor keeping what the viewer's
     * getModuleName answers in a String field (and a label from elsewhere in another), its own
     * size(), the marked add (List)V and insert (int, List)Z; and the extension.
     */
    private fun dataSourceClasses(
        addMarks: Int = 1,
        insertElsewhere: Boolean = false,
        keepsModule: Boolean = true,
        moduleWrittenLater: Boolean = false,
        sizeDeclared: Boolean = true,
        addLocals: Int = 3,
        addLoops: Boolean = false,
        extension: Boolean = true,
    ): List<ClassDef> {
        val moduleField = "$dataSource->module:$stringType"
        val asked = if (keepsModule) "getModuleName" else "getName"
        val methods = mutableListOf(
            method(dataSource, "<init>", listOf(viewer), "V", 1, body = """
                invoke-direct { p0 }, $objectType-><init>()V
                const-string v0, "a label"
                iput-object v0, p0, $dataSource->label:$stringType
                invoke-virtual { p1 }, $viewer->$asked()$stringType
                move-result-object v0
                iput-object v0, p0, $moduleField
                return-void
            """),
            method(dataSource, if (sizeDeclared) "size" else "count", emptyList(), "I", 1, body = """
                const/4 v0, 0x0
                return v0
            """),
            method(dataSource, "add", listOf(listType), "V", addLocals, body = buildString {
                if (addLoops) appendLine(":top")
                // 454's add loads a constant first, then its marker.
                appendLine("const/4 v1, 0x0")
                if (addMarks > 0) appendLine("const-string v0, \"android_purge_26_q2_$REELS_ADD_ITEMS\"")
                if (addLoops) appendLine("if-eqz p1, :top")
                appendLine("return-void")
            }),
        )
        if (addMarks > 1) {
            methods += method(dataSource, "clear", emptyList(), "V", 1, body = """
                const-string v0, "android_purge_26_q2_$REELS_ADD_ITEMS"
                return-void
            """)
        }
        if (moduleWrittenLater) {
            methods += method(dataSource, "reset", emptyList(), "V", 1, body = """
                const/4 v0, 0x0
                iput-object v0, p0, $moduleField
                return-void
            """)
        }
        val insertOwner = if (insertElsewhere) "Lfixture/OtherSource;" else dataSource
        val insert = method(insertOwner, "insert", listOf("I", listType), "Z", 3, body = """
            const-string v0, "android_purge_26_q2_$REELS_INSERT_ITEMS"
            const/4 v0, 0x1
            return v0
        """)
        val classes = if (insertElsewhere) {
            listOf(classDef(dataSource, methods), classDef(insertOwner, listOf(insert)))
        } else {
            listOf(classDef(dataSource, methods + insert))
        }
        return classes + if (extension) listOf(ExtensionDex.classDef(LIVE_REELS)) else emptyList()
    }

    /**
     * The Reels adapter shaped like 454's: the item with its kind, the kind with the placeholder and
     * another of its values as statics, and the adapter whose marked method asks the last item it
     * holds whether it's the placeholder, compares another field first, and has a compare a jump
     * lands in the middle of; and the extension.
     */
    private fun placeholderClasses(
        marked: Boolean = true,
        compares: Boolean = true,
        twoWays: Boolean = false,
        kindPublic: Boolean = true,
        placeholderPublic: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> {
        val itemClass = ImmutableClassDef(
            item, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, objectType, null, null, null,
            listOf(
                ImmutableField(item, "kind", kindType, if (kindPublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null),
                ImmutableField(item, "label", stringType, AccessFlags.PUBLIC.value, null, null, null),
            ),
            null,
        )
        val kindClass = ImmutableClassDef(
            kindType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, objectType, null, null, null,
            listOf("PLACEHOLDER", "REEL").map { name ->
                val access = (if (placeholderPublic || name != "PLACEHOLDER") AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or
                    AccessFlags.STATIC.value or AccessFlags.FINAL.value
                ImmutableField(kindType, name, kindType, access, null, null, null)
            },
            null,
        )
        val adds = method(adapter, "addClipsItems", listOf(item), "V", 3, body = buildString {
            if (marked) appendLine("const-string v0, \"android_purge_26_q2_$REELS_PLACEHOLDER\"")
            // Another field's compare with a static of its own type isn't the kind's.
            appendLine("iget-object v1, p1, $item->label:$stringType")
            appendLine("sget-object v0, $adapter->LABEL:$stringType")
            appendLine("if-eq v1, v0, :label")
            appendLine(":label")
            // A compare a jump lands in the middle of isn't straight code.
            appendLine("iget-object v1, p1, $item->kind:$kindType")
            appendLine(":middle")
            appendLine("sget-object v0, $kindType->REEL:$kindType")
            appendLine("if-eq v1, v0, :middle")
            if (compares) {
                appendLine("iget-object v1, p1, $item->kind:$kindType")
                appendLine("sget-object v0, $kindType->PLACEHOLDER:$kindType")
                appendLine("if-ne v1, v0, :add")
            }
            if (twoWays) {
                appendLine("iget-object v2, p1, $item->kind:$kindType")
                appendLine("sget-object v0, $kindType->REEL:$kindType")
                appendLine("if-eq v0, v2, :add")
            }
            appendLine(":add")
            appendLine("return-void")
        })
        val adapterClass = ImmutableClassDef(
            adapter, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, objectType, null, null, null,
            listOf(ImmutableField(adapter, "LABEL", stringType, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null)),
            listOf(adds),
        )
        return listOf(itemClass, kindClass, adapterClass) + if (extension) listOf(ExtensionDex.classDef(LIVE_REELS)) else emptyList()
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        public: Boolean = true,
        body: String,
    ): Method {
        val flags = if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value
        val total = registers + 1 + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(
        type: String,
        methods: List<Method>,
        staticFields: List<Pair<String, String>> = emptyList(),
        staticPublic: Boolean = true,
        interfaces: List<String> = emptyList(),
    ): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, objectType, interfaces, null, null,
        staticFields.map { (name, fieldType) ->
            val access = (if (staticPublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or
                AccessFlags.STATIC.value or AccessFlags.FINAL.value
            ImmutableField(type, name, fieldType, access, null, null, null)
        },
        methods,
    )

    // ---- reading code ---------------------------------------------------------------------------

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = when (val reference = reference()) {
        is TypeReference -> reference.type
        is FieldReference, is MethodReference -> reference.toString()
        else -> null
    }

    private fun Instruction.string(): String? = (reference() as? StringReference)?.string
}

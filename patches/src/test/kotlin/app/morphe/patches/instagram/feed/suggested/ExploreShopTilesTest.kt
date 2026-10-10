/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.explore.SECTIONS
import app.morphe.patches.instagram.explore.emptyExplorePages
import app.morphe.patches.instagram.explore.findExploreParser
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
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
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExploreShopTilesTest {
    /** The hook and the two stubs the patch writes are in the ExploreShopping the bundle ships. */
    @Test
    fun theHookAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(EXPLORE_SHOPPING).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(EXPLORE_SECTION_FILTER.substringAfter("->"), "content(Ljava/lang/Object;)Ljava/lang/Object;", "isTile(Ljava/lang/Object;)Z")) {
            assertTrue("$wanted is not in $EXPLORE_SHOPPING: $declared", wanted in declared)
        }
        assertEquals(SHOP_TILE, ExtensionDex.stringConstant(EXPLORE_SHOPPING, "SHOP_TILE"))
    }

    @Test
    fun theSectionsAreFound() {
        val found = PatchContexts.of(classes()).findExploreShopSections()

        assertEquals(SECTION_PARSER, found.parser)
        assertEquals(listOf(JSON), found.parameters)
        assertEquals(4, found.returnAt)
        assertEquals(0, found.register)
        assertEquals(SECTION, found.section)
        assertEquals("$SECTION->A01:$CONTENT", found.content.toString())
        assertEquals(TILE, found.tile)
    }

    /** The section the parser built goes past the extension on its way out, and the stubs read it. */
    @Test
    fun eachSectionGoesPastTheExtension() {
        val context = PatchContexts.of(classes())
        val found = context.findExploreShopSections()

        found.write(context)

        assertHooked("stand-in", context, found)
    }

    /** Hide the Explore grid's hook on the page parser, written first, changes nothing here. */
    @Test
    fun theSectionsAreFoundAfterHideTheExploreGrid() {
        val context = PatchContexts.of(classes())
        context.emptyExplorePages(context.findExploreParser())

        val found = context.findExploreShopSections()
        found.write(context)

        assertEquals(SECTION_PARSER, found.parser)
        assertHooked("after the grid", context, found)
    }

    @Test
    fun noExplorePageParserFailsThePatch() = refused("page parser", classes().filter { it.type != PAGE_PARSER })

    @Test
    fun twoSectionParsersFailThePatch() = refused("2 parsers", classes(secondParser = true))

    @Test
    fun aSectionBuiltTwiceFailsThePatch() = refused("2 times", classes(builtTwice = true))

    @Test
    fun noShopKindFailsThePatch() = refused("found []", classes(kinds = listOf("MEDIA", "CLIPS")))

    @Test
    fun twoTileTypesFailThePatch() = refused(OTHER_TILE, classes(secondTile = true))

    @Test
    fun aKindNamedByTwoEnumsFailsThePatch() = refused("whose type names all of", classes(sizes = listOf("ONE_BY_ONE", SHOP_TILE)))

    @Test
    fun aSectionTheExtensionCantReadFailsThePatch() {
        refused("isn't public", classes(sectionFlags = AccessFlags.FINAL.value))
        refused("isn't public", classes(holderFlags = AccessFlags.FINAL.value))
        refused("isn't public", classes(tileFlags = AccessFlags.FINAL.value))
    }

    @Test
    fun aSectionPastV15FailsThePatch() = refused("past v15", classes(sectionRegister = 16))

    @Test
    fun theStubsMissingFromTheExtensionFailWriting() {
        val context = PatchContexts.of(classes(extension = false) + emptyExtension())
        val found = context.findExploreShopSections()
        assertThrows(PatchException::class.java) { found.write(context) }
    }

    /**
     * In each declared build, Explore's page parser reads its sections with one section parser, the
     * one holding "DiscoverySection", and the section it built goes past the extension. On 450's
     * 385611438 that's LX/09CT, its section LX/090D and its tile LX/09DR.
     */
    @Test
    fun eachDeclaredBuildHooksExploresSections() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooksExploresSections(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other arm64 builds of each declared version. */
    @Test
    fun eachOtherBuildHooksExploresSections() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hooksExploresSections(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /**
     * Slices the fixture the way the finder reads it: the classes holding the sections' key or the
     * section parser's string, then the classes of each type the parser builds, the fields of those,
     * the fields of those, and theirs, so every candidate the finder weighs is there to weigh.
     */
    private fun hooksExploresSections(bundle: File, label: String) {
        val fieldTypes = HashMap<String, List<String>>()
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            val holding = dex.stringSection.any { it == SECTIONS || it == DISCOVERY_SECTION }
            for (classDef in dex.classes) {
                val types = classDef.instanceFields.map { it.type }.distinct()
                if (types.isNotEmpty()) fieldTypes[classDef.type] = types
                if (holding && classDef.methods.any { it.holds(SECTIONS) || it.holds(DISCOVERY_SECTION) }) {
                    holders += ImmutableClassDef.of(classDef)
                }
            }
        }
        val parser = holders.filter { holder -> holder.methods.any { it.holds(DISCOVERY_SECTION) } }
        assertEquals("$label: holders of $DISCOVERY_SECTION", 1, parser.size)
        val built: List<String> = parser.single().methods.flatMap { it.implementation?.instructions ?: emptyList() }
            .filter { it.opcode == Opcode.NEW_INSTANCE }.map { ((it as ReferenceInstruction).reference as TypeReference).type }
        var level: Set<String> = built.toSet()
        val wanted = HashSet(level)
        repeat(3) {
            level = level.flatMap { fieldTypes[it].orEmpty() }.toSet()
            wanted.addAll(level)
        }
        val sliced = FixtureDex.classes(bundle, wanted - holders.map { it.type }.toSet()).values + holders
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, sliced) + ExtensionDex.classDef(EXPLORE_SHOPPING))

        val found = context.findExploreShopSections()
        found.write(context)

        assertEquals("$label: the section parser", parser.single().type, found.parser)
        assertHooked("$label ${found.parser}", context, found)
        assertEveryReaderSkipsNull(bundle, label, found.parser)
    }

    /**
     * The hook answers null for a section holding a shop tile, so every reader of the section parser
     * has to drop a null section, as it does one that didn't parse. A reader takes the parser's
     * instance (its one static field of its own type) and parses through parseFromJsonParser, in one
     * of three shapes, each of which this holds to account:
     *  - A: the answer is tested for null right away (a check-cast may come first) and the branch
     *    goes past its use, as a list's add or a single field's store.
     *  - B: the instance goes to a static helper taking the reader, the parser and a collection,
     *    which parses, tests for null and adds, and whose code is checked to do exactly that.
     *  - C: the answer is cast to the section type and kept for later, and then every store of
     *    a section-typed object into a field in that method is directly behind a null test that
     *    branches past it, so the section can only be stored when it isn't null.
     * The whole APK is read for the instance and for any call into the parser from outside it.
     */
    private fun assertEveryReaderSkipsNull(bundle: File, label: String, parser: String) {
        val instance = FixtureDex.classes(bundle, setOf(parser)).getValue(parser).staticFields
            .filter { it.type == parser }.map { it.name }
        assertEquals("$label: the parser's instance field", 1, instance.size)
        val field = "$parser->${instance.single()}:$parser"
        val readers = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == parser } }) { method ->
            method.definingClass != parser && method.implementation?.instructions?.any {
                val reference = (it as? ReferenceInstruction)?.reference
                reference?.toString() == field || (reference as? MethodReference)?.definingClass == parser
            } == true
        }
        assertTrue("$label: no reader of $parser found", readers.size >= 3)
        val helpers = HashMap<String, Boolean>()
        val shapes = mutableListOf<String>()
        for (reader in readers) {
            val code = reader.implementation!!.instructions.toList()
            val address = IntArray(code.size + 1)
            for (i in code.indices) address[i + 1] = address[i] + code[i].codeUnits
            fun skips(test: Int): Boolean {
                val branch = code[test] as com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
                val index = address.indexOf(address[test] + branch.codeOffset)
                return index in code.indices && index != test + 1
            }
            fun names(at: Int) = ((code.getOrNull(at) as? ReferenceInstruction)?.reference as? MethodReference)?.name
            var reads = 0
            for (at in code.indices) {
                val reference = (code[at] as? ReferenceInstruction)?.reference
                val where = "$label: ${reader.definingClass}->${reader.name} at $at"
                if (reference is MethodReference && reference.definingClass == parser) {
                    fail("$where calls into the section parser itself: $reference")
                }
                if (reference?.toString() != field) continue
                assertEquals("$where: only a read of the instance", Opcode.SGET_OBJECT, code[at].opcode)
                val instanceRegister = (code[at] as OneRegisterInstruction).registerA
                val call = code[at + 1]
                if (call.opcode == Opcode.INVOKE_STATIC) {
                    // B: the instance is the helper's second argument.
                    val helper = (call as ReferenceInstruction).reference as MethodReference
                    val registers = call as FiveRegisterInstruction
                    assertEquals("$where: the instance goes to a helper", instanceRegister, registers.registerD)
                    val key = helper.toString()
                    helpers.getOrPut(key) {
                        val body = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it == helper.definingClass } }) {
                            it.definingClass == helper.definingClass && it.name == helper.name &&
                                it.parameterTypes.map(CharSequence::toString) == helper.parameterTypes.map(CharSequence::toString)
                        }.single().implementation!!.instructions.toList()
                        val ops = body.map { it.opcode }
                        ops == listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID) &&
                            (body[0] as ReferenceInstruction).reference.toString().let { it.contains("->parseFromJsonParser(") } &&
                            (body[2] as OneRegisterInstruction).registerA == (body[1] as OneRegisterInstruction).registerA &&
                            (body[3] as ReferenceInstruction).reference.toString().endsWith("->add(Ljava/lang/Object;)Z") &&
                            (body[3] as FiveRegisterInstruction).registerD == (body[1] as OneRegisterInstruction).registerA
                    }.also { assertTrue("$where: helper $key parses, tests for null and adds", it) }
                    shapes += "B"
                    reads++
                    continue
                }
                assertEquals("$where: parses through parseFromJsonParser", "parseFromJsonParser", names(at + 1))
                assertEquals("$where: on the instance", instanceRegister, (call as FiveRegisterInstruction).registerC)
                assertEquals("$where: keeps the answer", Opcode.MOVE_RESULT_OBJECT, code[at + 2].opcode)
                val answer = (code[at + 2] as OneRegisterInstruction).registerA
                var next = at + 3
                var cast: String? = null
                if (code[next].opcode == Opcode.CHECK_CAST) {
                    assertEquals("$where: casts the answer", answer, (code[next] as OneRegisterInstruction).registerA)
                    cast = ((code[next] as ReferenceInstruction).reference as TypeReference).type
                    next++
                }
                if (code[next].opcode == Opcode.IF_EQZ && (code[next] as OneRegisterInstruction).registerA == answer) {
                    assertTrue("$where: branches past its use of the section", skips(next))
                    shapes += "A"
                } else {
                    assertNotNull("$where: neither tested for null nor cast to a section", cast)
                    val stores = code.indices.filter { i ->
                        code[i].opcode == Opcode.IPUT_OBJECT && ((code[i] as ReferenceInstruction).reference as FieldReference).type == cast
                    }
                    assertTrue("$where: stores the section nowhere", stores.isNotEmpty())
                    for (store in stores) {
                        val guard = code[store - 1]
                        assertEquals("$where: the store at $store is behind a null test", Opcode.IF_EQZ, guard.opcode)
                        assertEquals("$where: that tests what it stores", (code[store] as OneRegisterInstruction).registerA, (guard as OneRegisterInstruction).registerA)
                        val branch = guard as com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
                        assertEquals("$where: and skips the store", address[store + 1], address[store - 1] + branch.codeOffset)
                    }
                    shapes += "C"
                }
                reads++
            }
            assertTrue("$label: ${reader.definingClass}->${reader.name} doesn't read the parser", reads > 0)
        }
        assertTrue("$label: shapes read ${shapes.sorted()}", shapes.isNotEmpty())
    }

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    /**
     * Right before the parser hands back the section it just built, the section goes to the
     * extension and what comes back is returned, once in the parser. The content stub reads the
     * section's content field and the tile stub tests for the tile.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, found: ExploreShopSections) {
        val code = context.mutableClassDefBy(found.parser).methods.single {
            it.name == "unsafeParseFromJson" && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.implementation!!.instructions.toList()
        val calls = code.indices.filter { code[it].calls(EXPLORE_SECTION_FILTER) }
        assertEquals("$what: one hook, where the return was", listOf(found.returnAt), calls)
        val at = calls.single()
        val call = code[at] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[at].opcode)
        assertEquals("$what: with the section", listOf(1, found.register), listOf(call.registerCount, call.registerC))
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
        assertEquals("$what: kept", found.register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: returned", Opcode.RETURN_OBJECT, code[at + 2].opcode)
        assertEquals("$what: returned", found.register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: built just before", Opcode.NEW_INSTANCE, code[at - 2].opcode)
        assertEquals("$what: a section", found.section, ((code[at - 2] as ReferenceInstruction).reference as TypeReference).type)

        val stubs = context.mutableClassDefBy(EXPLORE_SHOPPING).methods
        val content = stubs.single { it.name == "content" }.implementation!!.instructions.toList()
        assertEquals("$what: content", listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), content.take(3).map { it.opcode })
        assertEquals("$what: content cast", found.section, (content[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: content field", found.content.toString(), (content[1] as ReferenceInstruction).reference.toString())
        val read = content[1] as TwoRegisterInstruction
        assertEquals("$what: content read from the cast section", (content[0] as OneRegisterInstruction).registerA, read.registerB)
        assertEquals("$what: content returned", read.registerA, (content[2] as OneRegisterInstruction).registerA)
        val tile = stubs.single { it.name == "isTile" }.implementation!!.instructions.toList()
        assertEquals("$what: isTile", listOf(Opcode.INSTANCE_OF, Opcode.RETURN), tile.take(2).map { it.opcode })
        assertEquals("$what: isTile type", found.tile, (tile[0] as ReferenceInstruction).reference.toString())
        assertEquals("$what: isTile answer", (tile[0] as TwoRegisterInstruction).registerA, (tile[1] as OneRegisterInstruction).registerA)
    }

    private fun Instruction.calls(method: String) = (this as? ReferenceInstruction)?.reference?.toString() == method

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findExploreShopSections() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private companion object {
        const val DISCOVERY_SECTION = "DiscoverySection"
        const val PAGE_PARSER = "Lfixture/ExplorePageParser;"
        const val PAGE = "Lfixture/ExplorePage;"
        const val SECTION_PARSER = "Lfixture/SectionParser;"
        const val OTHER_PARSER = "Lfixture/OtherSectionParser;"
        const val SECTION = "Lfixture/Section;"
        const val CONTENT = "Lfixture/SectionContent;"
        const val TILE = "Lfixture/Tile;"
        const val OTHER_TILE = "Lfixture/OtherTile;"
        const val KIND = "Lfixture/TileKind;"
        const val SIZE = "Lfixture/TileSize;"
        const val LAYOUT = "Lfixture/Layout;"
        const val JSON = "Lfixture/JsonParser;"
        const val PUBLIC = 0x11 // public final

        fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<Instruction>) =
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            )

        fun type(type: String, flags: Int = PUBLIC, superclass: String = "Ljava/lang/Object;", fields: List<Pair<String, String>> = emptyList(), methods: List<ImmutableMethod> = emptyList(), fieldFlags: Int = PUBLIC) =
            ImmutableClassDef(
                type, flags, superclass, null, null, null,
                fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, fieldFlags, null, null, null) },
                methods,
            )

        fun enum(type: String, names: List<String>) = type(
            type, PUBLIC or AccessFlags.ENUM.value, "Ljava/lang/Enum;",
            methods = listOf(
                method(
                    type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1,
                    names.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )

        fun string(key: String) = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key))

        /** Reads [key]'s value with [parser]'s instance: sget-object, then parseFromJsonParser on it. */
        fun parse(parser: String) = listOf(
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, ImmutableFieldReference(parser, "A00", parser)),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 1, 5, 0, 0, 0,
                ImmutableMethodReference(parser, "parseFromJsonParser", listOf(JSON), "Ljava/lang/Object;"),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
        )

        /**
         * Shaped like 450's: Explore's page parser reads "sectional_items" with the section parser
         * and keeps the list in the page. The section parser holds "DiscoverySection", answers null
         * for JSON it can't read, and otherwise builds a section of a layout and its content and
         * hands it back. The content keeps a tile of its own, a list and a section to fall back on.
         * A tile has a size and a kind, and the kinds name the shop tile's.
         */
        fun classes(
            kinds: List<String> = listOf("MEDIA", "CLIPS", SHOP_TILE),
            sizes: List<String> = listOf("ONE_BY_ONE", "TWO_BY_TWO"),
            secondParser: Boolean = false,
            builtTwice: Boolean = false,
            secondTile: Boolean = false,
            sectionFlags: Int = PUBLIC,
            holderFlags: Int = PUBLIC,
            tileFlags: Int = PUBLIC,
            sectionRegister: Int = 0,
            extension: Boolean = true,
        ): List<ClassDef> {
            val page = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 3, ImmutableTypeReference(PAGE)),
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 3, 0, 0, 0, 0, ImmutableMethodReference(PAGE, "<init>", emptyList(), "V")),
                string(SECTIONS),
            ) + parse(SECTION_PARSER) + (if (secondParser) parse(OTHER_PARSER) else emptyList()) + listOf(
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 3, ImmutableFieldReference(PAGE, "A06", "Ljava/util/List;")),
                string("more_available"),
                ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 1, 3, ImmutableFieldReference(PAGE, "A09", "Z")),
                string("auto_load_more_enabled"),
                ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 1, 3, ImmutableFieldReference(PAGE, "A0A", "Z")),
                string("session_paging_token"),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3),
            )
            // The section in v[sectionRegister] with its layout and content after it, or in v0 with
            // them in v1 and v2. Past v15 the constructor is called with a range.
            val r = if (sectionRegister > 15) sectionRegister + 3 else 3
            fun build(into: Int): List<Instruction> {
                val init = ImmutableMethodReference(SECTION, "<init>", listOf(LAYOUT, CONTENT), "V")
                return listOf(
                    ImmutableInstruction21c(Opcode.NEW_INSTANCE, into, ImmutableTypeReference(SECTION)),
                    if (into > 15) ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, into, 3, init)
                    else ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, into, 1, 2, 0, 0, init),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, into),
                )
            }
            //   0 const-string   2 if-eqz +8   4 new-instance   6 invoke-direct   9 return   10 const/4   11 return
            val section = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(DISCOVERY_SECTION)),
                ImmutableInstruction21t(Opcode.IF_EQZ, 1, 8),
            ) + build(sectionRegister) + listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ) + (if (builtTwice) build(1) else emptyList())
            val parserMethod = method(
                SECTION_PARSER, "unsafeParseFromJson", listOf(JSON), "Ljava/lang/Object;", PUBLIC, r + 2, section,
            )
            val contentFields = listOf("A00" to TILE, "A01" to "Ljava/util/List;", "A02" to SECTION, "A03" to "I") +
                (if (secondTile) listOf("A04" to OTHER_TILE) else emptyList())
            val built = listOf(
                type(
                    PAGE_PARSER,
                    methods = listOf(method(PAGE_PARSER, "unsafeParseFromJson", listOf(JSON), "Ljava/lang/Object;", PUBLIC, 6, page)),
                ),
                type(PAGE, fields = listOf("A03" to "Ljava/lang/String;", "A06" to "Ljava/util/List;", "A09" to "Z", "A0A" to "Z")),
                type(SECTION_PARSER, methods = listOf(parserMethod)),
                type(SECTION, sectionFlags, fields = listOf("A00" to LAYOUT), fieldFlags = PUBLIC),
                type(CONTENT, fields = contentFields),
                type(TILE, tileFlags, fields = listOf("A00" to SIZE, "A01" to KIND, "A02" to "Ljava/lang/String;")),
                type(OTHER_TILE, fields = listOf("A00" to KIND)),
                enum(KIND, kinds),
                enum(SIZE, sizes),
                enum(LAYOUT, listOf("ONE_BY_TWO", "THREE_BY_FOUR")),
            ).map { if (it.type == SECTION) withContent(it, holderFlags) else it }
            return built + if (extension) listOf(ExtensionDex.classDef(EXPLORE_SHOPPING)) else emptyList()
        }

        /** The section with its content field, A01, given [flags]. */
        fun withContent(section: ClassDef, flags: Int): ClassDef = ImmutableClassDef(
            section.type, section.accessFlags, section.superclass, null, null, null,
            section.fields.map { ImmutableField.of(it) } + ImmutableField(section.type, "A01", CONTENT, flags, null, null, null),
            emptyList(),
        )

        /** An ExploreShopping with no stubs in it. */
        fun emptyExtension(): ClassDef = type(EXPLORE_SHOPPING)
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.photolocation

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep photo locations from Instagram (#628): the gallery file fetcher asks the extension before it
 * tests whether to ask MediaStore for the original, and both readers of a place ask it before they
 * read anything, on every build. Anything the patch can't pick out fails it before an instruction
 * changes.
 */
class KeepPhotoLocationsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for ((hook, parameters) in listOf(ASK_FOR_ORIGINAL to listOf("I"), GALLERY_ITEM_PLACE to emptyList(), EXIF_TAGS_PLACE to emptyList())) {
            val name = hook.substringAfter("->").substringBefore("(")
            val method = ExtensionDex.classDef(PHOTO_LOCATIONS).methods.single { it.name == name }
            assertEquals("$name's parameters", parameters, method.parameterTypes.map(CharSequence::toString))
            assertEquals("$name answers a boolean", "Z", method.returnType)
            assertTrue(name, AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }

    @Test
    fun theFetcherAsksBeforeTestingThePermission() {
        val request = PatchContexts.of(listOf(fetcher())).findOriginalRequest()
        assertEquals("the yes or no's register", GRANT, request.register)
        val stock = request.method.code()
        askBeforeOpeningTheOriginal(request)
        assertAsksFirst("stand-in", request.method.code(), request.index, request.register, stock)
    }

    @Test
    fun theReadersAnswerNoPlaceOnAYes() {
        val context = PatchContexts.of(listOf(galleryItem(), exifReader()))
        val item = context.findGalleryItemPlace()
        assertEquals("the gallery item's reader", "location", item.name)
        val itemStock = item.code()
        answerNoPlace(item, GALLERY_ITEM_PLACE)
        assertAnswersNoPlace("stand-in gallery item", item.code(), GALLERY_ITEM_PLACE, itemStock)

        val exif = context.findExifTagsPlace()
        assertEquals("the EXIF reader", "latLong", exif.name)
        val exifStock = exif.code()
        answerNoPlace(exif, EXIF_TAGS_PLACE)
        assertAnswersNoPlace("stand-in EXIF reader", exif.code(), EXIF_TAGS_PLACE, exifStock)
        assertEquals("the class's other readers of the tag stay", listOf(0, 0), listOf("writeLatLong", "latLongOf").map { name ->
            context.mutableClassDefBy(EXIF).methods.single { it.name == name }.code().count { it.referenceText() == EXIF_TAGS_PLACE }
        })
    }

    @Test
    fun aMissingOrDoubledFetcherFailsThePatch() {
        refuses("found 0", fetchers = listOf(fetcher(anchor = false)))
        refuses("found 0", fetchers = listOf(fetcher(asks = 0)))
        refuses("found 2", fetchers = listOf(fetcher(), fetcher("Lfixture/OtherFetcher;")))
        refuses("asks for an original 2 times", fetchers = listOf(fetcher(asks = 2)))
    }

    @Test
    fun aFetcherOfAnotherShapeFailsThePatch() {
        refuses("doesn't test the permission", fetchers = listOf(fetcher(tests = false)))
        refuses("doesn't keep the original's address", fetchers = listOf(fetcher(keeps = false)))
        refuses("doesn't skip just the ask", fetchers = listOf(fetcher(skipsJustTheAsk = false)))
        refuses("jumps onto its test of the permission", fetchers = listOf(fetcher(jumpedOnto = true)))
    }

    @Test
    fun aMissingOrDoubledReaderFailsThePatch() {
        refuses("found 0", galleryItems = listOf(galleryItem(anchor = false)))
        refuses("found 2", galleryItems = listOf(galleryItem(), galleryItem("Lfixture/OtherMedium;")))
        refuses("found 0", exifReaders = listOf(exifReader(anchor = false)))
        refuses("found 2", exifReaders = listOf(exifReader(), exifReader("Lfixture/OtherExif;")))
    }

    @Test
    fun aReaderOfAnotherShapeFailsThePatch() {
        refuses("is static", galleryItems = listOf(galleryItem(static = true)))
        refuses("doesn't take the content resolver", galleryItems = listOf(galleryItem(returns = "[I")))
        refuses("needs 1", galleryItems = listOf(galleryItem(locals = 0)))
        refuses("jumps back to its first instruction", galleryItems = listOf(galleryItem(jumpedBack = true)))
        refuses("doesn't take the EXIF tags", exifReaders = listOf(exifReader(takes = "I")))
        refuses("needs 1", exifReaders = listOf(exifReader(locals = 0)))
        refuses("jumps back to its first instruction", exifReaders = listOf(exifReader(jumpedBack = true)))
    }

    /** In each declared build: all three places ask the extension, with Instagram's own code left around them. */
    @Test
    fun eachDeclaredBuildAsksFirst() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildAsksFirstToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        fun sliced(anchor: String) = PatchContexts.of(FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, anchor)))

        val request = sliced(FETCHER_FAILED).findOriginalRequest()
        assertTrue("$label: the fetcher returns nothing", request.method.returnType == "V")
        val stock = request.method.code()
        askBeforeOpeningTheOriginal(request)
        assertAsksFirst(label, request.method.code(), request.index, request.register, stock)

        val item = sliced(MEDIA_LOCATION_FAILED).findGalleryItemPlace()
        val itemStock = item.code()
        answerNoPlace(item, GALLERY_ITEM_PLACE)
        assertAnswersNoPlace("$label gallery item", item.code(), GALLERY_ITEM_PLACE, itemStock)

        val exif = sliced(GPS_LATITUDE).findExifTagsPlace()
        val exifStock = exif.code()
        answerNoPlace(exif, EXIF_TAGS_PLACE)
        assertAnswersNoPlace("$label EXIF reader", exif.code(), EXIF_TAGS_PLACE, exifStock)
    }

    /**
     * The hook call over the yes or no, its answer back in that register, then the stock test, the ask
     * and its result; a no still lands just past the ask, and the rest of the code stays.
     */
    private fun assertAsksFirst(label: String, code: List<Instruction>, at: Int, register: Int, stock: List<Instruction>) {
        val ask = code[at]
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, ask.opcode)
        assertEquals("$label: the hook", ASK_FOR_ORIGINAL, ask.referenceText())
        assertEquals("$label: what it's handed", register, (ask as RegisterRangeInstruction).startRegister)
        assertEquals("$label: one register", 1, ask.registerCount)
        val answer = code[at + 1]
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, answer.opcode)
        assertEquals("$label: replaces what it asked about", register, (answer as OneRegisterInstruction).registerA)
        val test = code[at + 2]
        assertEquals("$label: the stock test follows", Opcode.IF_EQZ, test.opcode)
        assertEquals("$label: the test reads the answer", register, (test as OneRegisterInstruction).registerA)
        assertEquals("$label: then the ask for the original", REQUIRE_ORIGINAL, code[at + 3].referenceText())
        val units = code.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
        assertEquals("$label: a no skips just the ask", units[at + 5], units[at + 2] + (test as OffsetInstruction).codeOffset)
        assertEquals("$label: hook calls", 1, code.count { it.referenceText() == ASK_FOR_ORIGINAL })
        assertEquals("$label: the rest stays", texts(stock), texts(code.take(at) + code.drop(at + 2)))
    }

    /**
     * The reader opens with the hook: its answer in v0, a branch onto the first stock instruction when
     * it says no, and otherwise a null place handed back. The stock code follows untouched.
     */
    private fun assertAnswersNoPlace(label: String, code: List<Instruction>, hook: String, stock: List<Instruction>) {
        assertEquals("$label: the first call", hook, code[0].referenceText())
        assertEquals("$label: the call", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$label: the answer's register", 0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("$label: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$label: the branch reads the answer", 0, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$label: no place", Opcode.CONST_4, code[3].opcode)
        assertEquals("$label: no place is null", 0L, (code[3] as NarrowLiteralInstruction).narrowLiteral.toLong())
        assertEquals("$label: the early return", Opcode.RETURN_OBJECT, code[4].opcode)
        assertEquals("$label: it returns the null", 0, (code[4] as OneRegisterInstruction).registerA)
        val units = code.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
        assertEquals("$label: a no lands on the first stock instruction", units[5], units[2] + (code[2] as OffsetInstruction).codeOffset)
        assertEquals("$label: hook calls", 1, code.count { it.referenceText() == hook })
        assertEquals("$label: the rest stays", texts(stock), texts(code.drop(5)))
    }

    /** With one of the three places swapped for the classes given, the patch refuses for the reason given, and nothing has changed. */
    private fun refuses(
        reason: String,
        fetchers: List<ClassDef> = listOf(fetcher()),
        galleryItems: List<ClassDef> = listOf(galleryItem()),
        exifReaders: List<ClassDef> = listOf(exifReader()),
    ) {
        val classes = fetchers + galleryItems + exifReaders
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> texts(method.code()) } }
        val refusal = assertThrows(PatchException::class.java) { context.patchAll() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> texts(method.code()) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The patch's own order: every place found, then every hook written. */
    private fun BytecodePatchContext.patchAll() {
        val request = findOriginalRequest()
        val item = findGalleryItemPlace()
        val exif = findExifTagsPlace()
        askBeforeOpeningTheOriginal(request)
        answerNoPlace(item, GALLERY_ITEM_PLACE)
        answerNoPlace(exif, EXIF_TAGS_PLACE)
    }

    /**
     * The fetcher: loads its message, holds the yes or no in [GRANT] and the address in [URI], and on
     * a yes swaps the address for the original's.
     */
    private fun fetcher(
        type: String = "Lfixture/Fetcher;",
        anchor: Boolean = true,
        asks: Int = 1,
        tests: Boolean = true,
        keeps: Boolean = true,
        skipsJustTheAsk: Boolean = true,
        jumpedOnto: Boolean = false,
    ): ClassDef {
        val original = ImmutableMethodReference("Landroid/provider/MediaStore;", "setRequireOriginal", listOf("Landroid/net/Uri;"), "Landroid/net/Uri;")
        val other = ImmutableMethodReference("Lfixture/Uris;", "copy", listOf("Landroid/net/Uri;"), "Landroid/net/Uri;")
        val code = mutableListOf<ImmutableInstruction>()
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (anchor) FETCHER_FAILED else "another"))
        code += ImmutableInstruction11n(Opcode.CONST_4, GRANT, 1)
        code += ImmutableInstruction11n(Opcode.CONST_4, URI, 0)
        // Lands on the test of the permission, two code units on.
        if (jumpedOnto) code += ImmutableInstruction21t(Opcode.IF_NEZ, 1, 2)
        // The test, the ask and its result take six code units; seven lands one further.
        code += ImmutableInstruction21t(if (tests) Opcode.IF_EQZ else Opcode.IF_NEZ, GRANT, if (skipsJustTheAsk) 6 else 7)
        code += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, URI, 0, 0, 0, 0, if (asks > 0) original else other)
        code += if (keeps) ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, URI) else ImmutableInstruction11n(Opcode.CONST_4, URI, 0)
        code += ImmutableInstruction11n(Opcode.CONST_4, 0, 0)
        if (asks > 1) {
            code += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, URI, 0, 0, 0, 0, original)
            code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, URI)
        }
        code += ImmutableInstruction10x(Opcode.RETURN_VOID)
        return classOf(type, method(type, "loadData", listOf("Lfixture/Item;", "Lfixture/Request;"), "V", AccessFlags.PUBLIC.value, 7, code))
    }

    /** A gallery item's reader of its place, [locals] registers ahead of itself and the content resolver. */
    private fun galleryItem(
        type: String = MEDIUM,
        anchor: Boolean = true,
        static: Boolean = false,
        returns: String = "[D",
        locals: Int = 2,
        jumpedBack: Boolean = false,
    ): ClassDef {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        val self = if (static) 0 else 1
        val reader = method(
            type, "location", listOf("Landroid/content/ContentResolver;"), returns, flags, locals + self + 1,
            readerCode(MEDIA_LOCATION_FAILED, anchor, locals, jumpedBack),
        )
        return classOf(type, reader)
    }

    /**
     * The EXIF reader's class: the static reader of a place from the tags, beside its writer of the
     * camera's place and an instance reader, both loading the same tag.
     */
    private fun exifReader(
        type: String = EXIF,
        anchor: Boolean = true,
        takes: String = "Lfixture/Tags;",
        locals: Int = 2,
        jumpedBack: Boolean = false,
    ): ClassDef {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val reader = method(type, "latLong", listOf(takes), "[D", static, locals + 1, readerCode(GPS_LATITUDE, anchor, locals, jumpedBack))
        val writer = method(type, "writeLatLong", listOf("Lfixture/Tags;", "Landroid/location/Location;"), "V", static, 4, listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(GPS_LATITUDE)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ))
        val instance = method(type, "latLongOf", listOf("Lfixture/Tags;"), "[D", AccessFlags.PUBLIC.value, 4, listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(GPS_LATITUDE)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        ))
        return classOf(type, reader, writer, instance)
    }

    /** No place found: loads [anchor] and answers null, from v1 when there are locals to hold it. */
    private fun readerCode(anchor: String, loads: Boolean, locals: Int, jumpedBack: Boolean): List<ImmutableInstruction> {
        val spare = if (locals > 1) 1 else 0
        val code = mutableListOf<ImmutableInstruction>()
        code += ImmutableInstruction11n(Opcode.CONST_4, spare, 0)
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (loads) anchor else "another"))
        // Back onto the first instruction: one code unit for it and two for the string load.
        if (jumpedBack) code += ImmutableInstruction10t(Opcode.GOTO, -3)
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, spare)
        return code
    }

    private fun method(
        type: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<ImmutableInstruction>,
    ) = ImmutableMethod(
        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
        ImmutableMethodImplementation(registers, code, null, null),
    )

    private fun classOf(type: String, vararg methods: ImmutableMethod): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList(),
    )

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.referenceText() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun texts(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map {
        "${kind(it.opcode)} ${it.referenceText() ?: ""}"
    }

    /** An insert can push a branch past a plain goto's reach, and dexlib2 widens it. */
    private fun kind(opcode: Opcode) = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    private companion object {
        /** The fetcher's yes or no and the address it opens: two of its four locals. */
        const val GRANT = 2
        const val URI = 3

        const val MEDIUM = "Lfixture/Medium;"
        const val EXIF = "Lfixture/Exif;"
    }
}

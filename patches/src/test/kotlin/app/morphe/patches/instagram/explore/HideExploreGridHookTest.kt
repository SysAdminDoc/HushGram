/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HideExploreGridHookTest {
    /** The hook the patch writes is in the ExploreGrid the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDE_GRID.substringBefore("->")).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HIDE_GRID is not in the extension: $declared", HIDE_GRID.substringAfter("->") in declared)
    }

    @Test
    fun theParserIsFoundWithItsPageFields() {
        val context = PatchContexts.of(listOf(parser()))

        val found = context.findExploreParser()

        assertEquals(PARSER, found.type)
        assertEquals(PAGE, found.page)
        assertEquals(3, found.register)
        assertEquals("A06", found.sections.name)
        assertEquals("A09", found.moreAvailable.name)
        assertEquals("A0A", found.autoLoadMore.name)
    }

    /** On the way out, the page goes past the extension, and a yes empties it and turns off its "more" flags. */
    @Test
    fun thePageIsEmptiedOnTheWayOut() {
        val context = PatchContexts.of(listOf(parser()))

        context.emptyExplorePages(context.findExploreParser())

        assertEmptiedBeforeReturn("stand-in", context.mutableClassDefBy(PARSER).methods.single { it.name == "unsafeParseFromJson" })
    }

    /** The parser's loop jumps straight to its return, and that jump goes through the hook too. */
    @Test
    fun aBranchToTheReturnGoesThroughTheHook() {
        val context = PatchContexts.of(listOf(parser()))

        context.emptyExplorePages(context.findExploreParser())

        val code = context.mutableClassDefBy(PARSER).methods.single { it.name == "unsafeParseFromJson" }
            .implementation!!.instructions.toList()
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val branch = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        val target = addresses.indexOf(addresses[branch] + (code[branch] as OffsetInstruction).codeOffset)
        assertEquals(Opcode.IGET_OBJECT, code[target].opcode)
        assertEquals(Opcode.INVOKE_STATIC, code[target + 1].opcode)
        assertEquals(HIDE_GRID, (code[target + 1] as ReferenceInstruction).reference.toString())
    }

    @Test
    fun noParserFailsThePatch() {
        val context = PatchContexts.of(listOf(parser(pagingToken = "next_max_id")))
        assertThrows(PatchException::class.java) { context.findExploreParser() }
    }

    @Test
    fun twoParsersFailThePatch() {
        val context = PatchContexts.of(listOf(parser(), parser(type = "Lfixture/OtherExploreParser;")))
        assertThrows(PatchException::class.java) { context.findExploreParser() }
    }

    /** A flag read into anything but a boolean on the page is an update the patch hasn't seen. */
    @Test
    fun aFlagReadIntoSomethingElseFailsThePatch() {
        val context = PatchContexts.of(listOf(parser(moreAvailableType = "I")))
        assertThrows(PatchException::class.java) { context.findExploreParser() }
    }

    @Test
    fun theExtensionIsLeftAlone() {
        val own = parser(type = "Lapp/hushgram/extension/instagram/explore/Probe;")
        val context = PatchContexts.of(listOf(own, parser()))

        assertEquals(PARSER, context.findExploreParser().type)
    }

    /**
     * A key Redex asks a string pool for by number, as 450's 385611395 build does with
     * auto_load_more_enabled, is read back through the pool, and its flag is still turned off (#77).
     */
    @Test
    fun aPooledKeyIsReadThroughItsPool() {
        val context = PatchContexts.of(listOf(parser(pooledAutoLoad = true), pool()))

        val found = context.findExploreParser()
        context.emptyExplorePages(found)

        assertEquals("A0A", found.autoLoadMore.name)
        assertEmptiedBeforeReturn("pooled", context.mutableClassDefBy(PARSER).methods.single { it.name == "unsafeParseFromJson" })
    }

    /** A pooled key whose pool isn't there can't be told, and the patch fails. */
    @Test
    fun aPooledKeyWithoutItsPoolFailsThePatch() {
        val context = PatchContexts.of(listOf(parser(pooledAutoLoad = true)))
        val failure = assertThrows(PatchException::class.java) { context.findExploreParser() }
        assertTrue(failure.message!!, failure.message!!.contains("doesn't read $AUTO_LOAD_MORE"))
    }

    /**
     * In each declared build there's one topical Explore parser, its page fields are found, and after
     * the patch its return goes past the extension. On 449 that's LX/0AdL.
     */
    @Test
    fun eachDeclaredBuildEmptiesTheExplorePage() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                emptiesTheExplorePage(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other arm64 builds of each declared version, one of which pools a key (#77). */
    @Test
    fun eachOtherBuildEmptiesTheExplorePage() {
        for (apk in Fixtures.otherBuilds()) emptiesTheExplorePage(apk, apk.parentFile.name)
    }

    private fun emptiesTheExplorePage(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.holds(SECTIONS) }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, holders))

        val found = context.findExploreParser()
        context.emptyExplorePages(found)

        assertEquals("$label: the page's sections", "Ljava/util/List;", found.sections.type)
        assertEmptiedBeforeReturn(
            "$label ${found.type}",
            context.mutableClassDefBy(found.type).methods.single { it.name == "unsafeParseFromJson" },
        )
    }

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    /**
     * Right before the page's return: the sections are handed to the hook, and on a yes a new list
     * and two falses are written to the page.
     */
    private fun assertEmptiedBeforeReturn(what: String, method: Method) {
        val code = method.implementation!!.instructions.toList()
        val call = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_GRID }
        assertTrue("$what never asks the extension", call > 0)
        assertEquals("$what: one hook", 1, code.count { (it as? ReferenceInstruction)?.reference?.toString() == HIDE_GRID })
        val ret = (call until code.size).first { code[it].opcode == Opcode.RETURN_OBJECT }
        val page = (code[ret] as OneRegisterInstruction).registerA
        val read = code[call - 1]
        assertEquals("$what: the sections are read", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$what: from the page", page, (read as TwoRegisterInstruction).registerB)
        val writes = code.subList(call, ret).filter { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.IPUT_BOOLEAN }
        assertEquals(
            "$what: writes",
            listOf(Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BOOLEAN),
            writes.map { it.opcode },
        )
        assertTrue("$what: on the page", writes.all { (it as TwoRegisterInstruction).registerB == page })
        assertEquals(
            "$what: the new sections",
            "Ljava/util/ArrayList;",
            code.subList(call, ret).single { it.opcode == Opcode.NEW_INSTANCE }.let { (it as ReferenceInstruction).reference.toString() },
        )
        assertTrue("$what: the flags are falses", code.subList(call, ret).any { it.opcode == Opcode.CONST_4 })
        val fields = writes.map { ((it as ReferenceInstruction).reference as FieldReference).type }
        assertEquals("$what: field types", listOf("Ljava/util/List;", "Z", "Z"), fields)
    }

    private companion object {
        const val PARSER = "Lfixture/ExploreParser;"
        const val PAGE = "Lfixture/ExplorePage;"
        const val JSON = "Lfixture/JsonParser;"
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 7

        /**
         * Shaped like 449's: a new page, a branch to the return as the parser's loop has, then each
         * key followed by the write of its value into the page. [pooledAutoLoad] asks [pool] for one
         * key by number instead, three instructions and four more code units where the branch skips.
         */
        fun parser(
            type: String = PARSER,
            pagingToken: String = PAGING_TOKEN,
            moreAvailableType: String = "Z",
            pooledAutoLoad: Boolean = false,
        ): ClassDef {
            fun string(key: String) = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key))
            fun put(opcode: Opcode, value: Int, name: String, fieldType: String) =
                ImmutableInstruction22c(opcode, value, 3, ImmutableFieldReference(PAGE, name, fieldType))
            val moreOpcode = if (moreAvailableType == "Z") Opcode.IPUT_BOOLEAN else Opcode.IPUT
            val autoLoad = if (!pooledAutoLoad) listOf(string(AUTO_LOAD_MORE)) else listOf(
                ImmutableInstruction21s(Opcode.CONST_16, 0, POOLED),
                ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, ImmutableMethodReference(POOL, "A00", listOf("I"), "Ljava/lang/String;")),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            )
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 3, ImmutableTypeReference(PAGE)),
                ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 3, 0, 0, 0, 0, ImmutableMethodReference(PAGE, "<init>", emptyList(), "V")),
                ImmutableInstruction21t(Opcode.IF_EQZ, 5, if (pooledAutoLoad) 24 else 20),
                string("next_max_id"),
                put(Opcode.IPUT_OBJECT, 2, "A03", "Ljava/lang/String;"),
                string(SECTIONS),
                put(Opcode.IPUT_OBJECT, 2, "A06", "Ljava/util/List;"),
                string(MORE_AVAILABLE),
                put(moreOpcode, 1, "A09", moreAvailableType),
            ) + autoLoad + listOf(
                put(Opcode.IPUT_BOOLEAN, 1, "A0A", "Z"),
                string(pagingToken),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3),
            )
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        type, "unsafeParseFromJson", listOf(ImmutableMethodParameter(JSON, null, null)), "Ljava/lang/Object;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                        ImmutableMethodImplementation(6, code, null, null),
                    ),
                ),
            )
        }

        /**
         * A Redex string pool: a packed switch from [POOLED] to [AUTO_LOAD_MORE], anything else null.
         *   0 packed-switch p0, +8   3 const/4 v0   4 return-object   5 const-string   7 return-object   8 payload
         */
        fun pool(): ClassDef = ImmutableClassDef(
            POOL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    POOL, "A00", listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(2, listOf(
                        ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(AUTO_LOAD_MORE)),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED, 5))),
                    ), null, null),
                ),
            ),
        )
    }
}

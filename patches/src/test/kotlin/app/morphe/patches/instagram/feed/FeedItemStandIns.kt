/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import java.io.File

/** Stand-ins and fixture lookups for the patches that filter home feed items. */
internal object FeedItemStandIns {
    const val PARSER = "Lfixture/FeedItemParser;"
    const val ITEM = "Lfixture/FeedItem;"
    const val KIND = "Lfixture/FeedItemKind;"
    const val FETCH = "Lfixture/FetchReason;"
    const val MEDIA = "Lcom/instagram/feed/media/Media;"
    private const val JSON_PARSER = "Lfixture/JsonParser;"

    /**
     * Shaped like Instagram 449's: the parser, the feed item with its ClipsNetego field, two enum
     * fields and three post fields, the item's static helpers (one parsing from JSON, one wrapping a
     * post in the fields [postWrites] names) and the two enums with the names their static
     * initializers load.
     */
    fun classes(
        kindNames: List<String>,
        fetchNames: List<String> = listOf("COLD_START", "PULL_TO_REFRESH"),
        helpers: Int = 1,
        postWrites: List<String> = emptyList(),
    ): List<ClassDef> {
        val public = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val static = public or AccessFlags.STATIC.value
        fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, code: List<Instruction>) =
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, code, null, null),
            )
        fun enum(type: String, names: List<String>) = ImmutableClassDef(
            type, public or AccessFlags.ENUM.value, "Ljava/lang/Enum;", null, null, null, null,
            listOf(
                method(
                    type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 1,
                    names.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                        ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )
        val parse = listOf(
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(PARSER, "A00", PARSER)),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                ImmutableMethodReference(PARSER, "parseFromJsonParser", listOf(JSON_PARSER), "Ljava/lang/Object;"),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference(ITEM)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        val itemMethods = (1..helpers).map { method(ITEM, "A0${it + 1}", listOf(JSON_PARSER), ITEM, static, 2, parse) } +
            method(
                ITEM, "A01", listOf(MEDIA), ITEM, static, 2,
                listOf(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(ITEM))) +
                    postWrites.map { ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 0, ImmutableFieldReference(ITEM, it, MEDIA)) } +
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
        val itemClass = ImmutableClassDef(
            ITEM, public, "Ljava/lang/Object;", null, null, null,
            listOf(
                ImmutableField(ITEM, "A03", CLIPS_NETEGO, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(ITEM, "A0r", KIND, AccessFlags.PUBLIC.value, null, null, null),
                ImmutableField(ITEM, "A0s", FETCH, AccessFlags.PUBLIC.value, null, null, null),
            ) + listOf("A0u", "A0v", "A0w").map { ImmutableField(ITEM, it, MEDIA, AccessFlags.PUBLIC.value, null, null, null) },
            itemMethods,
        )
        val parserClass = ImmutableClassDef(
            PARSER, public, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                method(
                    PARSER, "unsafeParseFromJson", listOf(JSON_PARSER), "Ljava/lang/Object;",
                    public or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value, 3,
                    listOf(
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("media_or_ad")),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("clips_netego")),
                        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(ITEM)),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                ),
            ),
        )
        return listOf(parserClass, itemClass, enum(KIND, kindNames), enum(FETCH, fetchNames))
    }

    /** A declared build's feed item classes from one fixture bundle, and the item's type. */
    class Fixture(val bundle: File, val classes: List<ClassDef>, val itemType: String) {
        /** The item's static helper parsing one from JSON, as the fixture has it. */
        val helper: Method = classes.single { it.type == itemType }.methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.instructions().any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "parseFromJsonParser"
            }
        }
    }

    /**
     * Every fixture bundle of every Instagram build the catalog declares, with the classes the
     * parser holding "clips_netego" makes, the one of them with a ClipsNetego field (the item) and
     * its fields' types. Fails when a declared build has no fixture.
     */
    fun fixtures(): List<Fixture> {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val found = mutableListOf<Fixture>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = FixtureDex.classesHolding(bundle, "clips_netego")
                val made = holders.flatMap { it.methods }.flatMap { it.instructions() }
                    .filter { it.opcode == Opcode.NEW_INSTANCE }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                    .toSet()
                val candidates = FixtureDex.classes(bundle, made)
                val itemType = candidates.values.single { classDef -> classDef.fields.any { it.type == CLIPS_NETEGO } }.type
                val fieldTypes = candidates.getValue(itemType).fields.map { it.type }.toSet()
                val classes = (holders + candidates.values + FixtureDex.classes(bundle, fieldTypes).values).distinctBy { it.type }
                found += Fixture(bundle, classes, itemType)
            }
        }
        assertEquals(
            "a declared build has no fixture",
            versions,
            versions.filter { version -> found.any { it.bundle.name.contains("-$version-") } }.toSet(),
        )
        return found
    }

    /**
     * Asserts that each return of [helper] answers through [filters], in that order, each call
     * followed by its move-result and a cast back to the helper's type in the returned register.
     */
    fun assertFilteredBeforeReturn(what: String, helper: Method, vararg filters: String) {
        val code = helper.instructions()
        val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
        for (at in returns) {
            val returned = (code[at] as OneRegisterInstruction).registerA
            val start = at - 3 * filters.size
            filters.forEachIndexed { i, filter ->
                val call = start + 3 * i
                assertEquals(
                    "$what: the filter's opcodes",
                    listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST),
                    code.subList(call, call + 3).map { it.opcode },
                )
                assertEquals("$what: the filter called", filter, (code[call] as ReferenceInstruction).reference.toString())
                assertEquals(
                    "$what: the cast",
                    helper.returnType,
                    ((code[call + 2] as ReferenceInstruction).reference as TypeReference).type,
                )
                assertEquals("$what: the register cast", returned, (code[call + 2] as OneRegisterInstruction).registerA)
            }
        }
    }

    fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
}

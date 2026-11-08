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
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
    private val objectType = "Ljava/lang/Object;"

    /** The page filter and the stub the patch fills are in the LiveReels the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(LIVE_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(LIVE_PAGE_FILTER.substringAfter("->"), "livePreview(Ljava/lang/Object;)Ljava/lang/Object;")) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
    }

    /**
     * The key is the static field the marked method reads straight into the item's part lookup (a
     * part read through another register doesn't count), and the stub asks an item for that part and
     * answers null for anything else.
     */
    @Test
    fun theKeyIsTheOneTheLivePreviewCodeAsksForAndTheStubIsFilled() {
        val context = PatchContexts.of(classes())

        val live = context.findLivePreviewKey(item)
        context.fillLivePreview(item, live)

        assertEquals("$holder->KEY:$keyType", live.key.toString())
        assertEquals("$item->part($keyInterface)$objectType", live.lookup.toString())
        assertStubFilled("the stand-in", context, item, live)
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
            val read = state.code().filter { it.opcode == Opcode.SGET_OBJECT }.map { it.reference() as FieldReference }
            val types = read.map { it.definingClass } + read.map { it.type } + itemType
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
            assertStubFilled(bundle.name, context, itemType, live)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun assertStubFilled(what: String, context: BytecodePatchContext, itemType: String, live: LivePreviewKey) {
        val stub = context.mutableClassDefBy(LIVE_REELS).methods.single { it.name == "livePreview" }
        assertEquals("$what: the stub's registers", 2, stub.implementation!!.registerCount)
        assertEquals(
            "$what: the stub",
            listOf(
                Opcode.INSTANCE_OF to itemType, Opcode.IF_NEZ to null, Opcode.CONST_4 to null, Opcode.RETURN_OBJECT to null,
                Opcode.CHECK_CAST to itemType, Opcode.SGET_OBJECT to live.key.toString(),
                Opcode.INVOKE_VIRTUAL to live.lookup.toString(), Opcode.MOVE_RESULT_OBJECT to null, Opcode.RETURN_OBJECT to null,
            ),
            stub.code().map { it.opcode to it.referenceText() },
        )
    }

    // ---- stand-ins shaped like Instagram 454's --------------------------------------------------

    /**
     * The item with its part lookup; the key's interface, its type and the class holding it; another
     * part's key; the use case whose marked method asks the item for the live part first and another
     * part later; and the extension.
     */
    private fun classes(
        marks: Int = 1,
        asks: Boolean = true,
        twoKeys: Boolean = false,
        keyPublic: Boolean = true,
        lookupPublic: Boolean = true,
        keyImplements: Boolean = true,
        extension: Boolean = true,
    ): List<ClassDef> {
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
        fun useCaseClass(type: String) = classDef(type, listOf(method(type, "state", listOf(item), objectType, 2, body = buildString {
            appendLine("const-string v0, \"android_purge_26_q3_$LIVE_PREVIEW_STATE\"")
            if (asks) {
                appendLine("sget-object v0, $holder->KEY:$keyType")
                appendLine("invoke-virtual { p1, v0 }, $item->part($keyInterface)$objectType")
                appendLine("move-result-object v1")
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
        return listOf(itemClass, keyInterfaceClass, keyClass, holderClass, otherClass) + useCaseClasses +
            if (extension) listOf(ExtensionDex.classDef(LIVE_REELS)) else emptyList()
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

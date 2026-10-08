/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.instagram.feed.home.FEED_MEDIA_CACHE
import app.morphe.patches.instagram.feed.home.HOME_FEED_FILTER
import app.morphe.patches.instagram.feed.home.filterHomeFeedItems
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide suggested posts' post type switches: each item Home reads, from its feed response and its
 * store of the last run, goes through FeedSuggestions.homeItem, beside Hide the home feed's filter
 * in either order, and the stub reading a post's type reads the field the item's factory from a
 * post writes. A build where that field can't be told leaves everything as it was.
 */
class HomeFeedTypesHookTest {
    private val json = "Lfixture/JsonParser;"
    private val response = "Lfixture/HomeFeedResponse;"
    private val store = "Lfixture/HomeFeedStore;"
    private val chain = "Lfixture/ExploreChainResponse;"
    private val helper = ImmutableMethodReference(FeedItemStandIns.ITEM, "A02", listOf(json), FeedItemStandIns.ITEM)
    private val mediaType = "$FEED_SUGGESTIONS->mediaType(Ljava/lang/Object;)I"

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, vararg code: Instruction) =
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, code.toList(), null, null),
        )

    private fun type(owner: String, vararg methods: Method) =
        ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    private fun reader(owner: String, name: String, parameter: String, returns: String, vararg strings: String) =
        type(
            owner,
            method(
                owner, name, listOf(parameter), returns, 3,
                *(strings.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } + listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 2, 0, 0, 0, 0, helper),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                )).toTypedArray(),
            ),
        )

    /** A getter of Media answering an Integer and holding the hash of [field]. */
    private fun integerGetter(name: String, field: String) =
        method(
            MEDIA, name, emptyList(), "Ljava/lang/Integer;", 1,
            ImmutableInstruction31i(Opcode.CONST, 0, field.hashCode()),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )

    /** The feed item stand-ins, Home's response and store, Explore's chain, Media and the extension's FeedSuggestions. */
    private fun classes(postWrites: List<String> = listOf("A0u")): List<ClassDef> =
        FeedItemStandIns.classes(kindNames = listOf("MEDIA"), postWrites = postWrites) + listOf(
            reader(response, "unsafeParseFromJson", json, "Ljava/lang/Object;", "feed_items", "pull_to_refresh_window_ms"),
            reader(store, "A00", "[B", FeedItemStandIns.ITEM),
            type(FEED_MEDIA_CACHE, method(FEED_MEDIA_CACHE, "<init>", emptyList(), "V", 1,
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(store)),
                ImmutableInstruction10x(Opcode.RETURN_VOID))),
            reader(chain, "unsafeParseFromJson", json, "Ljava/lang/Object;", "chain_pagination_token", "more_available"),
            type(MEDIA, integerGetter("A6L", "like_count"), integerGetter("A6M", "media_type")),
            ExtensionDex.classDef(FEED_SUGGESTIONS),
        )

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SUGGESTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$FEED_SUGGESTIONS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HOME_TYPES_FILTER, mediaType)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    /** Home's two reads answer through homeItem; Explore's chain and the feed item helper don't. */
    @Test
    fun onlyHomesReadsAnswerThroughTheTypeFilter() {
        val context = PatchContexts.of(classes())

        assertEquals(2, requireNotNull(context.homeFeedTypesOrWarn()).write())

        for (owner in listOf(response, store)) {
            val code = context.mutableClassDefBy(owner).methods.single().instructions()
            assertEquals("$owner: the filters after the read", listOf(HOME_TYPES_FILTER), filtersAfterRead(code))
        }
        val chained = context.mutableClassDefBy(chain).methods.single().instructions()
        assertEquals("Explore's chain was touched", 5, chained.size)
        val item = context.mutableClassDefBy(FeedItemStandIns.ITEM)
        assertEquals("the helper was touched", 5, item.methods.single { it.name == "A02" }.instructions().size)
    }

    /**
     * The stub casts the item, reads the post field the factory writes, and answers the post's
     * media_type through the getter holding its hash, returning 0 on each path with nothing to read.
     */
    @Test
    fun theStubReadsTheFactorysPostFieldAndItsType() {
        val context = PatchContexts.of(classes())

        requireNotNull(context.homeFeedTypesOrWarn()).write()

        val code = context.mutableClassDefBy(FEED_SUGGESTIONS).methods.single { it.name == "mediaType" }.instructions()
        assertEquals(
            listOf(
                Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN,
            ),
            code.take(13).map { it.opcode },
        )
        assertEquals(FeedItemStandIns.ITEM, ((code[0] as ReferenceInstruction).reference as TypeReference).type)
        val post = (code[1] as ReferenceInstruction).reference as FieldReference
        assertEquals("A0u", post.name)
        assertEquals(MEDIA, post.type)
        val type = (code[5] as ReferenceInstruction).reference as MethodReference
        assertEquals("$MEDIA->A6M()Ljava/lang/Integer;", type.toString())
        assertEquals("Ljava/lang/Integer;->intValue()I", ((code[10] as ReferenceInstruction).reference as MethodReference).toString())
        val p0 = (code[0] as OneRegisterInstruction).registerA
        assertTrue("the body uses p0 alone", code.take(13).filterIsInstance<OneRegisterInstruction>().all { it.registerA == p0 })
    }

    /** With Hide the home feed in too, each read goes through both filters once, whichever applied first. */
    @Test
    fun besideHideTheHomeFeedEachReadPassesBothFiltersOnce() {
        for (homeFirst in listOf(true, false)) {
            val context = PatchContexts.of(classes())
            if (homeFirst) context.filterHomeFeedItems()
            requireNotNull(context.homeFeedTypesOrWarn()).write()
            if (!homeFirst) context.filterHomeFeedItems()

            for (owner in listOf(response, store)) {
                val filters = filtersAfterRead(context.mutableClassDefBy(owner).methods.single().instructions())
                assertEquals("$owner, home first $homeFirst", setOf(HOME_FEED_FILTER, HOME_TYPES_FILTER), filters.toSet())
                assertEquals("$owner, home first $homeFirst", 2, filters.size)
            }
        }
    }

    /** A factory writing no post field, or two, leaves the switches out and every class as it was. */
    @Test
    fun aPostFieldThatCantBeToldLeavesEverythingUnchanged() {
        for (writes in listOf(emptyList<String>(), listOf("A0u", "A0v"))) {
            val built = classes(postWrites = writes)
            val context = PatchContexts.of(built)
            val before = built.associate { it.type to it.methods.sumOf { m -> m.instructions().size } }

            assertNull("factory writing $writes", context.homeFeedTypesOrWarn())

            val after = built.associate { c -> c.type to context.classDefBy(c.type).methods.sumOf { it.instructions().size } }
            assertEquals(before, after)
        }
    }

    /** A feed item class or a media_type getter the extension can't reach leaves the switches out and every class as it was. */
    @Test
    fun whatTheExtensionCantReachLeavesEverythingUnchanged() {
        val hiddenGetter = ImmutableMethod(
            MEDIA, "A6M", emptyList(), "Ljava/lang/Integer;", AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(
                1,
                listOf(ImmutableInstruction31i(Opcode.CONST, 0, "media_type".hashCode()), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                null, null,
            ),
        )
        val privateGetter = classes().map { if (it.type == MEDIA) type(MEDIA, integerGetter("A6L", "like_count"), hiddenGetter) else it }
        val privateItem = classes().map { classDef ->
            if (classDef.type != FeedItemStandIns.ITEM) classDef else ImmutableClassDef(
                classDef.type, AccessFlags.FINAL.value, classDef.superclass, classDef.interfaces, classDef.sourceFile,
                classDef.annotations, classDef.fields, classDef.methods,
            )
        }
        for ((case, built) in listOf("private getter" to privateGetter, "private item" to privateItem)) {
            val context = PatchContexts.of(built)
            val before = built.associate { it.type to it.methods.sumOf { m -> m.instructions().size } }

            assertNull(case, context.homeFeedTypesOrWarn())

            val after = built.associate { c -> c.type to context.classDefBy(c.type).methods.sumOf { it.instructions().size } }
            assertEquals(case, before, after)
        }
    }

    /**
     * In each declared build, the post field the item's factory from a post writes is the one its
     * parser fills from "media_or_ad", and Media has one getter for media_type.
     */
    @Test
    fun eachDeclaredBuildFindsThePostAndItsType() {
        for (fixture in FeedItemStandIns.fixtures()) {
            val name = fixture.bundle.name
            val context = PatchContexts.of(fixture.classes)

            val post = context.itemPost(fixture.itemType)
            val getter = context.pandoGetter("test", MEDIA, "media_type", "Ljava/lang/Integer;")
            assertTrue("$name: Media is public", AccessFlags.PUBLIC.isSet(context.classDefBy(MEDIA).accessFlags))
            assertTrue("$name: ${getter.name} is public", AccessFlags.PUBLIC.isSet(getter.accessFlags))

            val parsers = fixture.classes.flatMap { it.methods }.filter { method ->
                method.returnType == "Ljava/lang/Object;" && method.parameterTypes.size == 1 &&
                    method.holds("media_or_ad") && method.holds("clips_netego")
            }
            val parser = parsers.singleOrNull() ?: error("$name: expected one feed item parser, found ${parsers.size}")
            val filled = parser.instructions().filter { it.opcode == Opcode.IPUT_OBJECT }
                .map { (it as ReferenceInstruction).reference as FieldReference }
                .filter { it.definingClass == fixture.itemType && it.type == MEDIA }
                .map { it.name }
            assertTrue("$name: the parser fills ${post.name} among $filled", post.name in filled)
        }
    }

    /** The extension filters right after each read of the helper in [code], in order. */
    private fun filtersAfterRead(code: List<Instruction>): List<String> {
        val reads = code.indices.filter { code[it].calls(helper.toString()) }
        assertEquals("one read", 1, reads.size)
        val after = reads.single() + 2
        return generateSequence(after) { it + 3 }
            .takeWhile { it < code.size && code[it].opcode == Opcode.INVOKE_STATIC_RANGE }
            .map { ((code[it] as ReferenceInstruction).reference as MethodReference).toString() }
            .toList()
    }

    private fun Instruction.calls(reference: String): Boolean =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun Method.holds(string: String): Boolean =
        instructions().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string }
}
